/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.elasticsearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.kie.kogito.persistence.api.query.AttributeFilter;
import org.kie.kogito.persistence.api.query.AttributeSort;
import org.kie.kogito.persistence.api.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;

/**
 * Elasticsearch implementation of the Query interface.
 *
 * <p>Supports:
 * <ul>
 *   <li>Filtering (including JSON flattened field queries)
 *   <li>Sorting
 *   <li>Pagination (limit, offset)
 * </ul>
 *
 * <p><b>JSON Field Queries</b>:
 * Filters marked with {@code isJson() == true} are treated as flattened field queries.
 * For example: {@code input.customerId} → flattened field query on "input.customerId"
 *
 * @param <T> Model type
 */
public class ElasticsearchQuery<T> implements Query<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchQuery.class);

    private final ElasticsearchClient client;
    private final String indexName;
    private final Class<T> modelClass;

    private Integer limit;
    private Integer offset;
    private final List<AttributeFilter<?>> filters = new ArrayList<>();
    private final List<AttributeSort> sortBy = new ArrayList<>();

    public ElasticsearchQuery(ElasticsearchClient client, String indexName, Class<T> modelClass) {
        this.client = client;
        this.indexName = indexName;
        this.modelClass = modelClass;
    }

    @Override
    public Query<T> limit(Integer limit) {
        this.limit = limit;
        return this;
    }

    @Override
    public Query<T> offset(Integer offset) {
        this.offset = offset;
        return this;
    }

    @Override
    public Query<T> filter(List<AttributeFilter<?>> filters) {
        this.filters.addAll(filters);
        return this;
    }

    @Override
    public Query<T> sort(List<AttributeSort> sortBy) {
        this.sortBy.addAll(sortBy);
        return this;
    }

    @Override
    public List<T> execute() {
        try {
            SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                    .index(indexName);

            // Build query from filters
            if (!filters.isEmpty()) {
                BoolQuery.Builder boolQuery = QueryBuilders.bool();
                for (AttributeFilter<?> filter : filters) {
                    addFilterToBoolQuery(boolQuery, filter);
                }
                requestBuilder.query(q -> q.bool(boolQuery.build()));
            }

            // Apply sorting
            if (!sortBy.isEmpty()) {
                for (AttributeSort sort : sortBy) {
                    final String field = sort.getAttribute();
                    final SortOrder order = sort.getSort() == org.kie.kogito.persistence.api.query.SortDirection.ASC
                            ? SortOrder.Asc
                            : SortOrder.Desc;
                    requestBuilder.sort(s -> s.field(f -> f.field(field).order(order)));
                }
            }

            // Apply pagination
            if (offset != null) {
                requestBuilder.from(offset);
            }
            if (limit != null) {
                requestBuilder.size(limit);
            }

            // Execute search
            SearchResponse<T> response = client.search(requestBuilder.build(), modelClass);

            // Extract results
            List<T> results = new ArrayList<>();
            for (Hit<T> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    results.add(hit.source());
                }
            }

            LOGGER.debug("Elasticsearch query returned {} results from index {}", results.size(), indexName);
            return results;

        } catch (IOException e) {
            throw new RuntimeException("Failed to execute Elasticsearch query on index: " + indexName, e);
        }
    }

    @Override
    public long count() {
        try {
            SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                    .index(indexName)
                    .size(0); // Don't return documents, just count

            // Build query from filters
            if (!filters.isEmpty()) {
                BoolQuery.Builder boolQuery = QueryBuilders.bool();
                for (AttributeFilter<?> filter : filters) {
                    addFilterToBoolQuery(boolQuery, filter);
                }
                requestBuilder.query(q -> q.bool(boolQuery.build()));
            }

            SearchResponse<T> response = client.search(requestBuilder.build(), modelClass);
            return response.hits().total() != null ? response.hits().total().value() : 0;

        } catch (IOException e) {
            throw new RuntimeException("Failed to count documents in index: " + indexName, e);
        }
    }

    /**
     * Add filter to bool query.
     *
     * <p>Handles JSON filters (marked with isJson() == true) as flattened field queries.
     */
    private void addFilterToBoolQuery(BoolQuery.Builder boolQuery, AttributeFilter<?> filter) {
        String attribute = filter.getAttribute();
        Object value = filter.getValue();

        // JSON filters are already in dot-notation format (e.g., "input.customerId")
        // No special handling needed - ES flattened fields use dot notation directly

        switch (filter.getCondition()) {
            case EQUAL:
                boolQuery.must(m -> m.term(t -> t.field(attribute).value(toFieldValue(value))));
                break;

            case IN:
                if (value instanceof List<?> values) {
                    boolQuery.must(m -> m.terms(t -> t
                            .field(attribute)
                            .terms(v -> v.value(values.stream()
                                    .map(this::toFieldValue)
                                    .toList()))));
                }
                break;

            case LIKE:
                // Convert wildcard pattern (* -> *)
                String pattern = value.toString().replace('*', '*');
                boolQuery.must(m -> m.wildcard(w -> w.field(attribute).value(pattern)));
                break;

            case GT:
                boolQuery.must(m -> m.range(r -> r.field(attribute).gt(toJsonData(value))));
                break;

            case GTE:
                boolQuery.must(m -> m.range(r -> r.field(attribute).gte(toJsonData(value))));
                break;

            case LT:
                boolQuery.must(m -> m.range(r -> r.field(attribute).lt(toJsonData(value))));
                break;

            case LTE:
                boolQuery.must(m -> m.range(r -> r.field(attribute).lte(toJsonData(value))));
                break;

            case IS_NULL:
                boolQuery.mustNot(n -> n.exists(e -> e.field(attribute)));
                break;

            case NOT_NULL:
                boolQuery.must(m -> m.exists(e -> e.field(attribute)));
                break;

            case BETWEEN:
                if (value instanceof List<?> values) {
                    if (values.size() == 2) {
                        boolQuery.must(m -> m.range(r -> r
                                .field(attribute)
                                .gte(toJsonData(values.get(0)))
                                .lte(toJsonData(values.get(1)))));
                    }
                }
                break;

            default:
                LOGGER.warn("Unsupported filter condition: {} for field: {}", filter.getCondition(), attribute);
        }
    }

    /**
     * Convert value to ES field value.
     */
    private FieldValue toFieldValue(Object value) {
        if (value instanceof String) {
            return FieldValue.of((String) value);
        } else if (value instanceof Number) {
            return FieldValue.of(((Number) value).longValue());
        } else if (value instanceof Boolean) {
            return FieldValue.of((Boolean) value);
        } else if (value instanceof Enum) {
            return FieldValue.of(value.toString());
        } else {
            return FieldValue.of(value.toString());
        }
    }

    /**
     * Convert value to JSON data for range queries.
     */
    private JsonData toJsonData(Object value) {
        return JsonData.of(value);
    }
}
