package com.lagu.platform.search.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the review's finding: SearchRequest had no upper bound on size/page,
 * and no cap on filters/sort/facets — an unauthenticated caller (consumerSearch is public) could
 * request size=100000 or dozens of terms aggregations in one call, a free DoS/aggregation
 * primitive against OpenSearch.
 */
class SearchRequestValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private SearchRequest baseRequest() {
        SearchRequest req = new SearchRequest();
        req.setObjectType("VENUE");
        return req;
    }

    @Test
    void defaultRequestIsValid() {
        assertThat(validator.validate(baseRequest())).isEmpty();
    }

    @Test
    void rejectsSizeAboveCap() {
        SearchRequest req = baseRequest();
        req.setSize(100_000);

        Set<ConstraintViolation<SearchRequest>> violations = validator.validate(req);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("size"));
    }

    @Test
    void rejectsZeroOrNegativeSize() {
        SearchRequest req = baseRequest();
        req.setSize(0);
        assertThat(validator.validate(req)).anyMatch(v -> v.getPropertyPath().toString().equals("size"));

        req.setSize(-5);
        assertThat(validator.validate(req)).anyMatch(v -> v.getPropertyPath().toString().equals("size"));
    }

    @Test
    void rejectsNegativePage() {
        SearchRequest req = baseRequest();
        req.setPage(-1);

        assertThat(validator.validate(req)).anyMatch(v -> v.getPropertyPath().toString().equals("page"));
    }

    @Test
    void acceptsSizeAtTheCap() {
        SearchRequest req = baseRequest();
        req.setSize(100);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void rejectsTooManyFacets() {
        SearchRequest req = baseRequest();
        req.setFacets(IntStream.range(0, 11).mapToObj(i -> "field" + i).collect(Collectors.toList()));

        assertThat(validator.validate(req)).anyMatch(v -> v.getPropertyPath().toString().equals("facets"));
    }

    @Test
    void rejectsTooManyFilters() {
        SearchRequest req = baseRequest();
        Map<String, Object> filters = IntStream.range(0, 51).boxed()
                .collect(Collectors.toMap(i -> "field" + i, i -> (Object) ("value" + i)));
        req.setFilters(filters);

        assertThat(validator.validate(req)).anyMatch(v -> v.getPropertyPath().toString().equals("filters"));
    }

    @Test
    void rejectsTooManySortCriteria() {
        SearchRequest req = baseRequest();
        SortCriteria s = new SortCriteria();
        s.setField("name");
        s.setOrder("asc");
        req.setSort(List.of(s, s, s, s, s, s, s, s, s, s, s));

        assertThat(validator.validate(req)).anyMatch(v -> v.getPropertyPath().toString().equals("sort"));
    }
}
