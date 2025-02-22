package io.tiledb.spark;

import static io.tiledb.spark.util.addEpsilon;
import static io.tiledb.spark.util.generateAllSubarrays;
import static io.tiledb.spark.util.subtractEpsilon;
import static org.apache.log4j.Priority.ERROR;
import static org.apache.spark.metrics.TileDBMetricsSource.dataSourceBuildRangeFromFilterTimerName;
import static org.apache.spark.metrics.TileDBMetricsSource.dataSourceCheckAndMergeRangesTimerName;
import static org.apache.spark.metrics.TileDBMetricsSource.dataSourceComputeNeededSplitsToReduceToMedianVolumeTimerName;
import static org.apache.spark.metrics.TileDBMetricsSource.dataSourcePlanBatchInputPartitionsTimerName;
import static org.apache.spark.metrics.TileDBMetricsSource.dataSourcePruneColumnsTimerName;
import static org.apache.spark.metrics.TileDBMetricsSource.dataSourcePushFiltersTimerName;
import static org.apache.spark.metrics.TileDBMetricsSource.dataSourceReadSchemaTimerName;

import io.tiledb.java.api.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.log4j.Logger;
import org.apache.spark.TaskContext;
import org.apache.spark.metrics.TileDBReadMetricsUpdater;
import org.apache.spark.sql.sources.And;
import org.apache.spark.sql.sources.EqualNullSafe;
import org.apache.spark.sql.sources.EqualTo;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.sources.GreaterThan;
import org.apache.spark.sql.sources.GreaterThanOrEqual;
import org.apache.spark.sql.sources.In;
import org.apache.spark.sql.sources.LessThan;
import org.apache.spark.sql.sources.LessThanOrEqual;
import org.apache.spark.sql.sources.Or;
import org.apache.spark.sql.sources.v2.reader.*;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.vectorized.ColumnarBatch;

public class TileDBDataSourceReader
    implements DataSourceReader,
        SupportsPushDownRequiredColumns,
        SupportsScanColumnarBatch,
        SupportsPushDownFilters {

  static Logger log = Logger.getLogger(TileDBDataSourceReader.class.getName());
  private final TileDBReadMetricsUpdater metricsUpdater;

  private URI uri;
  private TileDBReadSchema tileDBReadSchema;
  private TileDBDataSourceOptions tiledbOptions;
  private Filter[] pushedFilters;

  public TileDBDataSourceReader(URI uri, TileDBDataSourceOptions options) {
    this.uri = uri;
    this.tiledbOptions = options;
    this.tileDBReadSchema = new TileDBReadSchema(uri, options);
    this.metricsUpdater = new TileDBReadMetricsUpdater(TaskContext.get());
  }

  @Override
  public StructType readSchema() {
    metricsUpdater.startTimer(dataSourceReadSchemaTimerName);
    log.trace("Reading schema for " + uri);
    StructType schema = tileDBReadSchema.getSparkSchema();
    log.trace("Read schema for " + uri + ": " + schema);
    metricsUpdater.finish(dataSourceReadSchemaTimerName);
    return schema;
  }

  @Override
  public void pruneColumns(StructType pushDownSchema) {
    metricsUpdater.startTimer(dataSourcePruneColumnsTimerName);
    log.trace("Set pushdown columns for " + uri + ": " + pushDownSchema);
    tileDBReadSchema.setPushDownSchema(pushDownSchema);
    metricsUpdater.finish(dataSourcePruneColumnsTimerName);
  }

  @Override
  public Filter[] pushFilters(Filter[] filters) {
    metricsUpdater.startTimer(dataSourcePushFiltersTimerName);
    log.trace("size of filters " + filters.length);
    ArrayList<Filter> pushedFiltersList = new ArrayList<>();
    ArrayList<Filter> leftOverFilters = new ArrayList<>();

    // Loop through all filters and check if they are support type and on a domain. If so push them
    // down
    for (Filter filter : filters) {
      if (filterCanBePushedDown(filter)) {
        pushedFiltersList.add(filter);
      } else {
        leftOverFilters.add(filter);
      }
    }

    this.pushedFilters = new Filter[pushedFiltersList.size()];
    this.pushedFilters = pushedFiltersList.toArray(this.pushedFilters);

    Filter[] leftOvers = new Filter[leftOverFilters.size()];
    leftOvers = leftOverFilters.toArray(leftOvers);
    metricsUpdater.finish(dataSourcePushFiltersTimerName);
    return leftOvers;
  }

  private boolean filterCanBePushedDown(Filter filter) {
    if (filter instanceof And) {
      And f = (And) filter;
      if (filterCanBePushedDown(f.left()) && filterCanBePushedDown(f.right())) {
        return true;
      }
    } else if (filter instanceof EqualNullSafe) {
      return true;
    } else if (filter instanceof EqualTo) {
      return true;
    } else if (filter instanceof GreaterThan) {
      return true;
    } else if (filter instanceof GreaterThanOrEqual) {
      return true;
    } else if (filter instanceof LessThan) {
      return true;
    } else if (filter instanceof LessThanOrEqual) {
      return true;
    }
    return false;
  }

  @Override
  public Filter[] pushedFilters() {
    return pushedFilters;
  }

  @Override
  public boolean enableBatchRead() {
    // always read in batch mode
    return true;
  }

  @Override
  public List<InputPartition<ColumnarBatch>> planBatchInputPartitions() {
    metricsUpdater.startTimer(dataSourcePlanBatchInputPartitionsTimerName);
    ArrayList<InputPartition<ColumnarBatch>> readerPartitions = new ArrayList<>();

    try {
      Context ctx = new Context(tiledbOptions.getTileDBConfigMap(true));
      // Fetch the array and load its metadata
      Array array = new Array(ctx, uri.toString());
      HashMap<String, Pair> nonEmptyDomain = array.nonEmptyDomain();
      Domain domain = array.getSchema().getDomain();

      List<List<Range>> ranges = new ArrayList<>();
      // Populate initial range list
      for (int i = 0; i < domain.getNDim(); i++) {
        ranges.add(new ArrayList<>());
      }

      for (int i = 0; i < array.getSchema().getAttributeNum(); i++) {
        ranges.add(new ArrayList<>());
      }

      // Build range from all pushed filters
      for (Filter filter : pushedFilters) {
        List<List<Range>> allRanges = buildRangeFromFilter(filter, nonEmptyDomain).getFirst();
        for (int i = 0; i < allRanges.size(); i++) {
          ranges.get(i).addAll(allRanges.get(i));
        }
      }

      // Add nonEmptyDomain to any dimension that does not have a range from pushdown
      // For any existing ranges we try to merge into super ranges
      for (int i = 0; i < domain.getNDim() + array.getSchema().getAttributeNum(); i++) {
        List<Range> range = ranges.get(i);
        if (range.isEmpty()) {
          String columnName = this.tileDBReadSchema.getColumnName(i).get();
          if (this.tileDBReadSchema.hasDimension(columnName)) {
            range.add(new Range(nonEmptyDomain.get(columnName)));
          } else {
            range.add(new Range(true, new Pair(null, null)));
          }
        } else {
          List<Range> mergedRanges = checkAndMergeRanges(range);
          ranges.set(i, mergedRanges);
        }
      }

      List<SubArrayRanges> subarrays = new ArrayList<>();

      generateAllSubarrays(
          ranges.subList(0, (int) (domain.getNDim())), subarrays, 0, new ArrayList<>());

      int availablePartitions = tiledbOptions.getPartitionCount();
      if (availablePartitions > 1) {
        // Base case where we don't have any (or just single) pushdown per dimension
        if (subarrays.size() == 1 && subarrays.get(0).splittable()) {
          subarrays = subarrays.get(0).splitToPartitions(availablePartitions);
        } else {
          // Sort subarrays based on volume so largest volume is first
          subarrays.sort(Collections.reverseOrder());
          // Find median volume of subarrays;

          SubArrayRanges medianSubarray = subarrays.get(subarrays.size() / 2);
          Number medianVolume = medianSubarray.getVolume();

          List<Integer> neededSplitsToReduceToMedianVolume =
              computeNeededSplitsToReduceToMedianVolume(
                  subarrays.subList(0, subarrays.size() / 2),
                  medianVolume,
                  medianSubarray.getDatatype());

          int sumOfNeededSplitsForEvenDistributed =
              neededSplitsToReduceToMedianVolume.stream().mapToInt(Integer::intValue).sum();

          for (int i = 0; i < neededSplitsToReduceToMedianVolume.size(); i++) {
            SubArrayRanges subarray = subarrays.get(i);

            // Don't try to split unsplittable subarrays
            if (!subarray.splittable()) {
              continue;
            }

            /*
             Imprecision with double division don't matter here we just want close enough percentages;

             The weights are computed based on the percentage of needed splits to reduce to the median.
             For example if we have 5 subarrays each with volumes of 10, 15, 50, 200, 400
             The median is 50
             The number of splits to reduce to the median is 400 / 50 = 8 AND 200 / 50 = 4
             The weights are computed to be
             8 / (8+4) = 0.66 and 4 / (8+4) = 0.33 for subarrays 400 and 200 respectively.
             If the number of available splits is 3 (thus we can not give the full 8 + 4 splits needed for the median)
             We do 0.66 * 3 = 2 splits to the 400 and 3 * 0.33 = 1 splits to the 200 subarray.
             This results in a final subarray volumes of 10, 15, 50, 100, 100, 100, 100, 100, 100
            */
            int numberOfWeightedSplits =
                (int)
                    Math.ceil(
                        neededSplitsToReduceToMedianVolume.get(i).doubleValue()
                            / sumOfNeededSplitsForEvenDistributed
                            * availablePartitions);
            List<SubArrayRanges> splitSubarray = subarray.split(numberOfWeightedSplits);

            subarrays.remove(i);
            subarrays.addAll(splitSubarray);
          }
        }
      }

      List<List<Range>> attributeRanges = new ArrayList<>();
      for (int i = ((int) domain.getNDim());
          i < domain.getNDim() + array.getSchema().getAttributeNum();
          i++) {
        attributeRanges.add(ranges.get(i));
      }

      for (SubArrayRanges subarray : subarrays) {
        // In the future we will be smarter about combining ranges to have partitions work on more
        // than one range
        // I.E. don't over partition like we probably are doing now
        List<List<Range>> subarrayRanges = new ArrayList<>();
        subarrayRanges.add(subarray.getRanges());
        readerPartitions.add(
            new TileDBDataReaderPartition(
                uri, tileDBReadSchema, tiledbOptions, subarrayRanges, attributeRanges));
      }

    } catch (TileDBError tileDBError) {
      log.log(ERROR, tileDBError.getMessage());
      metricsUpdater.finish(dataSourcePlanBatchInputPartitionsTimerName);
      return readerPartitions;
    }
    metricsUpdater.finish(dataSourcePlanBatchInputPartitionsTimerName);
    return readerPartitions;
  }

  /**
   * Computes the number of splits needed to reduce a subarray to a given size
   *
   * @param subArrayRanges
   * @param medianVolume
   * @param datatype
   * @return
   */
  private List<Integer> computeNeededSplitsToReduceToMedianVolume(
      List<SubArrayRanges> subArrayRanges, Number medianVolume, Class datatype) {
    metricsUpdater.startTimer(dataSourceComputeNeededSplitsToReduceToMedianVolumeTimerName);
    List<Integer> neededSplits = new ArrayList<>();
    for (SubArrayRanges subArrayRange : subArrayRanges) {
      Number volume = subArrayRange.getVolume();
      if (datatype == Byte.class) {
        neededSplits.add(volume.byteValue() / medianVolume.byteValue());
      } else if (datatype == Short.class) {
        neededSplits.add(volume.shortValue() / medianVolume.shortValue());
      } else if (datatype == Integer.class) {
        neededSplits.add(volume.intValue() / medianVolume.intValue());
      } else if (datatype == Long.class) {
        neededSplits.add(((Long) (volume.longValue() / medianVolume.longValue())).intValue());
      } else if (datatype == Float.class) {
        neededSplits.add(((Float) (volume.floatValue() / medianVolume.floatValue())).intValue());
      } else if (datatype == Double.class) {
        neededSplits.add(((Double) (volume.doubleValue() / medianVolume.doubleValue())).intValue());
      }
    }
    metricsUpdater.finish(dataSourceComputeNeededSplitsToReduceToMedianVolumeTimerName);
    return neededSplits;
  }

  /**
   * Check and merge any and all ranges
   *
   * @param range
   * @return
   * @throws TileDBError
   */
  private List<Range> checkAndMergeRanges(List<Range> range) throws TileDBError {
    metricsUpdater.startTimer(dataSourceCheckAndMergeRangesTimerName);
    List<Range> rangesToBeMerged = new ArrayList<>(range);
    Collections.sort(rangesToBeMerged);

    boolean mergeable = true;
    while (mergeable) {
      List<Range> mergedRange = new ArrayList<>();
      for (int i = 0; i < rangesToBeMerged.size(); i++) {

        // If we are at the last range in the list it means the last_range - 1 and last_range were
        // not mergeable
        // OR it means we have a list of 1, either way the only thing to do is add this last range
        // to the list
        // and break
        if (i == rangesToBeMerged.size() - 1) {
          mergedRange.add(rangesToBeMerged.get(i));
          break;
        }

        Range left = rangesToBeMerged.get(i);
        Range right = rangesToBeMerged.get(i + 1);
        if (left.canMerge(right)) {
          mergedRange.add(left.merge(right));
          i++;
        } else {
          mergedRange.add(left);
        }
      }

      // If the merged ranges is the same size as the unmerged ranges it means there is was no
      // merges possible
      // and we have completed the merge process
      if (mergedRange.size() == rangesToBeMerged.size()) {
        mergeable = false;
      }

      // Override original ranges with new merged Ranges
      rangesToBeMerged = new ArrayList<>(mergedRange);
    }

    metricsUpdater.finish(dataSourceCheckAndMergeRangesTimerName);
    return rangesToBeMerged;
  }

  /**
   * Returns the minimum value for the given datatype
   *
   * @param datatype
   * @return
   */
  private Object getMinValue(Class datatype) {
    if (datatype == Byte.class) {
      return Byte.MIN_VALUE;
    } else if (datatype == Short.class) {
      return Short.MIN_VALUE;
    } else if (datatype == Integer.class) {
      return Integer.MIN_VALUE;
    } else if (datatype == Long.class) {
      return Long.MIN_VALUE;
    } else if (datatype == Float.class) {
      return Float.MIN_VALUE;
    } else {
      return null;
    }
  }

  /**
   * Returns the maximum value for the given datatype
   *
   * @param datatype
   * @return
   */
  private Object getMaxValue(Class datatype) {
    if (datatype == Byte.class) {
      return Byte.MAX_VALUE;
    } else if (datatype == Short.class) {
      return Short.MAX_VALUE;
    } else if (datatype == Integer.class) {
      return Integer.MAX_VALUE;
    } else if (datatype == Long.class) {
      return Long.MAX_VALUE;
    } else if (datatype == Float.class) {
      return Float.MAX_VALUE;
    } else {
      return null;
    }
  }

  /**
   * Creates range(s) from a filter that has been pushed down
   *
   * @param filter
   * @param nonEmptyDomain
   * @throws TileDBError
   */
  private Pair<List<List<Range>>, Class> buildRangeFromFilter(
      Filter filter, HashMap<String, Pair> nonEmptyDomain) throws TileDBError {
    metricsUpdater.startTimer(dataSourceBuildRangeFromFilterTimerName);
    Class filterType = filter.getClass();
    // Map<String, Integer> dimensionIndexing = new HashMap<>();
    List<List<Range>> ranges = new ArrayList<>();
    // Build mapping for dimension name to index
    for (int i = 0; i < this.tileDBReadSchema.dimensionIndex.size(); i++) {
      ranges.add(new ArrayList<>());
    }

    for (int i = 0; i < this.tileDBReadSchema.attributeIndex.size(); i++) {
      ranges.add(new ArrayList<>());
    }
    // First handle filter that are AND this is something like dim1 >= 1 AND dim1 <= 10
    // Could also be dim1 between 1 and 10
    if (filter instanceof And) {
      Pair<List<List<Range>>, Class> left =
          buildRangeFromFilter(((And) filter).left(), nonEmptyDomain);
      Pair<List<List<Range>>, Class> right =
          buildRangeFromFilter(((And) filter).right(), nonEmptyDomain);

      int dimIndex =
          IntStream.range(0, left.getFirst().size())
              .filter(e -> left.getFirst().get(e).size() > 0)
              .findFirst()
              .getAsInt();

      // Create return constructed ranges
      List<List<Range>> constructedRanges = new ArrayList<>();
      for (int i = 0; i < Math.max(left.getFirst().size(), right.getFirst().size()); i++)
        constructedRanges.add(new ArrayList<>());

      // Switch on the left side to see if it is the greater than or less than clause and set
      // appropriate position
      Pair<Object, Object> newPair = new Pair<>(null, null);
      if (left.getSecond() == GreaterThan.class || left.getSecond() == GreaterThanOrEqual.class) {
        newPair.setFirst(left.getFirst().get(dimIndex).get(0).getFirst());
      } else if (left.getSecond() == LessThan.class || left.getSecond() == LessThanOrEqual.class) {
        newPair.setSecond(left.getFirst().get(dimIndex).get(0).getSecond());
      }

      // Next switch on the right side to see if it is the greater than or less than clause and set
      // appropriate position
      if (right.getSecond() == GreaterThan.class || right.getSecond() == GreaterThanOrEqual.class) {
        newPair.setFirst(right.getFirst().get(dimIndex).get(0).getFirst());
      } else if (right.getSecond() == LessThan.class
          || right.getSecond() == LessThanOrEqual.class) {
        newPair.setSecond(right.getFirst().get(dimIndex).get(0).getSecond());
      }

      // Set the range
      List<Range> constructedRange = new ArrayList<Range>();
      constructedRange.add(new Range(newPair));

      constructedRanges.set(dimIndex, constructedRange);

      return new Pair<>(constructedRanges, filterType);
      // Handle Or clauses as recursive calls
    } else if (filter instanceof Or) {
      Pair<List<List<Range>>, Class> left =
          buildRangeFromFilter(((Or) filter).left(), nonEmptyDomain);
      Pair<List<List<Range>>, Class> right =
          buildRangeFromFilter(((Or) filter).right(), nonEmptyDomain);
      for (int i = 0; i < left.getFirst().size(); i++) {
        while (right.getFirst().size() < i) {
          right.getFirst().add(new ArrayList<>());
        }

        right.getFirst().get(i).addAll(left.getFirst().get(i));
      }

      return right;
      // Equal and EqualNullSafe are just straight dim1 = 1 fields. Set both side of range to single
      // value
    } else if (filter instanceof EqualNullSafe) {
      EqualNullSafe f = (EqualNullSafe) filter;
      int columnIndex = this.tileDBReadSchema.getColumnId(f.attribute()).get();
      ranges.get(columnIndex).add(new Range(new Pair<>(f.value(), f.value())));
    } else if (filter instanceof EqualTo) {
      EqualTo f = (EqualTo) filter;
      int columnIndex = this.tileDBReadSchema.getColumnId(f.attribute()).get();
      ranges.get(columnIndex).add(new Range(new Pair<>(f.value(), f.value())));

      // GreaterThan is ranges which are in the form of `dim > 1`
    } else if (filter instanceof GreaterThan) {
      GreaterThan f = (GreaterThan) filter;
      Object second;
      if (nonEmptyDomain.get(f.attribute()) != null)
        second = nonEmptyDomain.get(f.attribute()).getSecond();
      else second = getMaxValue(f.value().getClass());
      int columnIndex = this.tileDBReadSchema.getColumnId(f.attribute()).get();
      ranges
          .get(columnIndex)
          .add(
              new Range(
                  new Pair<>(
                      addEpsilon(
                          (Number) f.value(), this.tileDBReadSchema.columnTypes.get(columnIndex)),
                      second)));
    } else if (filter instanceof GreaterThanOrEqual) {
      GreaterThanOrEqual f = (GreaterThanOrEqual) filter;
      Object second;
      if (nonEmptyDomain.get(f.attribute()) != null)
        second = nonEmptyDomain.get(f.attribute()).getSecond();
      else second = getMaxValue(f.value().getClass());
      int columnIndex = this.tileDBReadSchema.getColumnId(f.attribute()).get();
      ranges.get(columnIndex).add(new Range(new Pair<>(f.value(), second)));

      // For in filters we will add every value as ranges of 1. `dim IN (1, 2, 3)`
    } else if (filter instanceof In) {
      In f = (In) filter;
      // Add every value as a new range, TileDB will collapse into super ranges for us
      for (Object value : f.values()) {
        int dimIndex = this.tileDBReadSchema.getColumnId(f.attribute()).get();
        ranges.get(dimIndex).add(new Range(new Pair<>(value, value)));
      }

      // LessThan is ranges which are in the form of `dim < 1`
    } else if (filter instanceof LessThan) {
      LessThan f = (LessThan) filter;
      Object first;
      if (nonEmptyDomain.get(f.attribute()) != null)
        first = nonEmptyDomain.get(f.attribute()).getSecond();
      else first = getMinValue(f.value().getClass());
      int columnIndex = this.tileDBReadSchema.getColumnId(f.attribute()).get();
      ranges
          .get(columnIndex)
          .add(
              new Range(
                  new Pair<>(
                      first,
                      subtractEpsilon(
                          (Number) f.value(),
                          this.tileDBReadSchema.columnTypes.get(columnIndex)))));
      // LessThanOrEqual is ranges which are in the form of `dim <= 1`
    } else if (filter instanceof LessThanOrEqual) {
      LessThanOrEqual f = (LessThanOrEqual) filter;
      Object first;
      if (nonEmptyDomain.get(f.attribute()) != null)
        first = nonEmptyDomain.get(f.attribute()).getSecond();
      else first = getMinValue(f.value().getClass());
      int columnIndex = this.tileDBReadSchema.getColumnId(f.attribute()).get();
      ranges.get(columnIndex).add(new Range(new Pair<>(first, f.value())));
    } else {
      throw new TileDBError("Unsupported filter type");
    }
    metricsUpdater.finish(dataSourceBuildRangeFromFilterTimerName);
    return new Pair<>(ranges, filterType);
  }
}
