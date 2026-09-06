package com.rtdwh.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
class TaskParameterServiceTest {
    private final TaskParameterService service = new TaskParameterService(new ObjectMapper());
    private final String schema = "[{\"name\":\"region\",\"type\":\"string\",\"required\":true},{\"name\":\"limit\",\"type\":\"integer\",\"defaultValue\":10}]";
    @Test void freezesDefaultsAndEscapesValuesWithoutRecursiveExpansion() {
        String parameters = service.normalize(schema, "{\"region\":\"a' OR 1=1 -- ${limit}\"}");
        assertEquals("{\"region\":\"a' OR 1=1 -- ${limit}\",\"limit\":10}", parameters);
        assertEquals("select * from t where region='a'' OR 1=1 -- ${limit}' and dt='2026-09-05' limit 10",
                service.render("select * from t where region='${region}' and dt='${bizdate}' limit ${limit}", schema, parameters, LocalDate.of(2026,9,5)));
    }
    @Test void rejectsUnknownMissingAndWrongTypeBeforeCreatingInstances() {
        assertThrows(IllegalArgumentException.class, () -> service.normalize(schema, "{}"));
        assertThrows(IllegalArgumentException.class, () -> service.normalize(schema, "{\"region\":\"east\",\"other\":1}"));
        assertThrows(IllegalArgumentException.class, () -> service.normalize(schema, "{\"region\":\"east\",\"limit\":\"1; drop table t\"}"));
        assertThrows(IllegalArgumentException.class, () -> service.normalize(schema, "{\"region\":\"east\",\"limit\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> service.normalize(schema, "{\"region\":\"east\",\"bizdate\":\"2020-01-01\"}"));
    }
    @Test void rejectsUndeclaredUnusedAndInvalidDefaultsOnPublish() {
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplate("select ${missing}", "[]"));
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplate("select 1", schema));
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplate("select ${n}", "[{\"name\":\"n\",\"type\":\"integer\",\"defaultValue\":\"invalid\"}]"));
    }
    @Test void cannotInsertParametersIntoIdentifiersCommentsOrPartialStrings() {
        for (String sql : new String[]{"select * from `table_${region}`", "select '${region}_suffix'", "select ${region}suffix", "select 1 -- ${region}"})
            assertThrows(IllegalArgumentException.class, () -> service.render(sql, schema, "{\"region\":\"east\"}", null));
    }
    @Test void validatesDateBooleanAndBoundedNumericLiterals() {
        assertThrows(IllegalArgumentException.class, () -> service.normalize("[{\"name\":\"d\",\"type\":\"date\"}]", "{\"d\":\"2026-02-30\"}"));
        assertThrows(IllegalArgumentException.class, () -> service.normalize("[{\"name\":\"b\",\"type\":\"boolean\"}]", "{\"b\":\"true\"}"));
        assertThrows(IllegalArgumentException.class, () -> service.normalize("[{\"name\":\"n\",\"type\":\"number\"}]", "{\"n\":1e1000000}"));
        assertEquals("select TRUE, '2026-09-05'", service.render("select ${b}, ${d}", "[{\"name\":\"b\",\"type\":\"boolean\"},{\"name\":\"d\",\"type\":\"date\"}]", "{\"b\":true,\"d\":\"2026-09-05\"}", null));
    }
    @Test void accessCheckUsesLiteralPlaceholdersAndLegacyCannotInjectSql() {
        assertEquals("select * from t where dt=NULL and n=NULL", service.forAccessCheck("select * from t where dt='${bizdate}' and n=${n}"));
        assertEquals("select 'x''; drop table t; --'", service.render("select ${value}", null, "{\"value\":\"x'; drop table t; --\"}", null));
    }
}
