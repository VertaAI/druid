/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import org.apache.druid.guice.annotations.ExtensionPoint;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.query.datasourcemetadata.DataSourceMetadataQuery;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.metadata.metadata.SegmentMetadataQuery;
import org.apache.druid.query.scan.ScanQuery;
import org.apache.druid.query.search.SearchQuery;
import org.apache.druid.query.select.SelectQuery;
import org.apache.druid.query.spec.QuerySegmentSpec;
import org.apache.druid.query.timeboundary.TimeBoundaryQuery;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.topn.TopNQuery;
import org.apache.druid.segment.Segment;
import org.apache.druid.segment.VirtualColumns;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ExtensionPoint
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "queryType")
@JsonSubTypes(value = {
    @JsonSubTypes.Type(name = Query.TIMESERIES, value = TimeseriesQuery.class),
    @JsonSubTypes.Type(name = Query.SEARCH, value = SearchQuery.class),
    @JsonSubTypes.Type(name = Query.TIME_BOUNDARY, value = TimeBoundaryQuery.class),
    @JsonSubTypes.Type(name = Query.GROUP_BY, value = GroupByQuery.class),
    @JsonSubTypes.Type(name = Query.SCAN, value = ScanQuery.class),
    @JsonSubTypes.Type(name = Query.SEGMENT_METADATA, value = SegmentMetadataQuery.class),
    @JsonSubTypes.Type(name = Query.SELECT, value = SelectQuery.class),
    @JsonSubTypes.Type(name = Query.TOPN, value = TopNQuery.class),
    @JsonSubTypes.Type(name = Query.DATASOURCE_METADATA, value = DataSourceMetadataQuery.class)
})
public interface Query<T>
{
  String TIMESERIES = "timeseries";
  String SEARCH = "search";
  String TIME_BOUNDARY = "timeBoundary";
  String GROUP_BY = "groupBy";
  String SCAN = "scan";
  String SEGMENT_METADATA = "segmentMetadata";
  String SELECT = "select";
  String TOPN = "topN";
  String DATASOURCE_METADATA = "dataSourceMetadata";

  DataSource getDataSource();

  boolean hasFilters();

  DimFilter getFilter();

  String getType();

  QueryRunner<T> getRunner(QuerySegmentWalker walker);

  List<Interval> getIntervals();

  Duration getDuration();

  // currently unused, but helping enforce the idea that all queries have a Granularity
  @SuppressWarnings("unused")
  Granularity getGranularity();

  DateTimeZone getTimezone();

  /**
   * Use {@link #getQueryContext()} instead.
   */
  @Deprecated
  Map<String, Object> getContext();

  /**
   * Returns QueryContext for this query. This type distinguishes between user provided, system default, and system
   * generated query context keys so that authorization may be employed directly against the user supplied context
   * values.
   *
   * This method is marked @Nullable, but is only so for backwards compatibility with Druid versions older than 0.23.
   * Callers should check if the result of this method is null, and if so, they are dealing with a legacy query
   * implementation, and should fall back to using {@link #getContext()} and {@link #withOverriddenContext(Map)} to
   * manipulate the query context.
   *
   * Note for query context serialization and deserialization.
   * Currently, once a query is serialized, its queryContext can be different from the original queryContext
   * after the query is deserialized back. If the queryContext has any {@link QueryContext#defaultParams} or
   * {@link QueryContext#systemParams} in it, those will be found in {@link QueryContext#userParams}
   * after it is deserialized. This is because {@link BaseQuery#getContext()} uses
   * {@link QueryContext#getMergedParams()} for serialization, and queries accept a map for deserialization.
   */
  @Nullable
  default QueryContext getQueryContext()
  {
    return null;
  }

  <ContextType> ContextType getContextValue(String key);

  <ContextType> ContextType getContextValue(String key, ContextType defaultValue);

  boolean getContextBoolean(String key, boolean defaultValue);

  boolean isDescending();

  /**
   * Comparator that represents the order in which results are generated from the
   * {@link QueryRunnerFactory#createRunner(Segment)} and
   * {@link QueryRunnerFactory#mergeRunners(QueryProcessingPool, Iterable)} calls. This is used to combine streams of
   * results from different sources; for example, it's used by historicals to combine streams from different segments,
   * and it's used by the broker to combine streams from different historicals.
   *
   * Important note: sometimes, this ordering is used in a type-unsafe way to order @{code Result<BySegmentResultValue>}
   * objects. Because of this, implementations should fall back to {@code Ordering.natural()} when they are given an
   * object that is not of type T.
   */
  Ordering<T> getResultOrdering();

  Query<T> withOverriddenContext(Map<String, Object> contextOverride);

  /**
   * Returns a new query, identical to this one, but with a different associated {@link QuerySegmentSpec}.
   *
   * This often changes the behavior of {@link #getRunner(QuerySegmentWalker)}, since most queries inherit that method
   * from {@link BaseQuery}, which implements it by calling {@link QuerySegmentSpec#lookup}.
   */
  Query<T> withQuerySegmentSpec(QuerySegmentSpec spec);

  Query<T> withId(String id);

  @Nullable
  String getId();

  /**
   * Returns a copy of this query with a new subQueryId (see {@link #getSubQueryId()}.
   */
  Query<T> withSubQueryId(String subQueryId);

  @SuppressWarnings("unused")
  default Query<T> withDefaultSubQueryId()
  {
    return withSubQueryId(UUID.randomUUID().toString());
  }

  /**
   * Returns the subQueryId of this query. This is set by ClientQuerySegmentWalker (the entry point for the Broker's
   * query stack) on any subqueries that it issues. It is null for the main query.
   */
  @Nullable
  String getSubQueryId();

  default Query<T> withSqlQueryId(String sqlQueryId)
  {
    return this;
  }

  @Nullable
  default String getSqlQueryId()
  {
    return getContextValue(BaseQuery.SQL_QUERY_ID);
  }

  /**
   * Returns a most specific ID of this query; if it is a subquery, this will return its subquery ID.
   * If it is a regular query without subqueries, this will return its query ID.
   * This method should be called after the relevant ID is assigned using {@link #withId} or {@link #withSubQueryId}.
   */
  default String getMostSpecificId()
  {
    final String subqueryId = getSubQueryId();
    return subqueryId == null ? Preconditions.checkNotNull(getId(), "queryId") : subqueryId;
  }

  Query<T> withDataSource(DataSource dataSource);

  default Query<T> optimizeForSegment(PerSegmentQueryOptimizationContext optimizationContext)
  {
    return this;
  }

  default Query<T> withPriority(int priority)
  {
    return withOverriddenContext(ImmutableMap.of(QueryContexts.PRIORITY_KEY, priority));
  }

  default Query<T> withLane(String lane)
  {
    return withOverriddenContext(ImmutableMap.of(QueryContexts.LANE_KEY, lane));
  }

  default VirtualColumns getVirtualColumns()
  {
    return VirtualColumns.EMPTY;
  }

  /**
   * Returns the set of columns that this query will need to access out of its datasource.
   *
   * This method does not "look into" what the datasource itself is doing. For example, if a query is built on a
   * {@link QueryDataSource}, this method will not return the columns used by that subquery. As another example, if a
   * query is built on a {@link JoinDataSource}, this method will not return the columns from the underlying datasources
   * that are used by the join condition, unless those columns are also used by this query in other ways.
   *
   * Returns null if the set of required columns cannot be known ahead of time.
   */
  @Nullable
  default Set<String> getRequiredColumns()
  {
    return null;
  }
}
