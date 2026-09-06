package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportParameterRendererTest {
    private final ReportParameterRenderer renderer = new ReportParameterRenderer(new ObjectMapper());
    private final String config = """
            {"parameters":[
              {"name":"start_date","type":"date","required":true,"defaultValue":"2026-08-01"},
              {"name":"region","type":"string","required":true},
              {"name":"min_amount","type":"number","defaultValue":10},
              {"name":"channels","type":"stringList"}
            ]}
            """;

    @Test
    void rendersTypedLiteralsAndEscapesStrings() {
        String sql = renderer.render("select * from orders where dt >= {{start_date}} "
                        + "and region={{region}} and amount >= {{min_amount}} and channel in {{channels}}",
                config, Map.of("region", "east' OR 1=1 --", "channels", List.of("app", "mini")));

        assertEquals("select * from orders where dt >= '2026-08-01' "
                        + "and region='east'' OR 1=1 --' and amount >= 10 and channel in ('app','mini')", sql);
    }

    @Test
    void rejectsUnknownAndMissingParameters() {
        String required = "[{\"name\":\"region\",\"type\":\"string\",\"required\":true}]";
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render("select * from t where region={{region}}", required, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render("select * from t where region={{region}}", required,
                        Map.of("region", "east", "extra", "value")));
    }

    @Test
    void rejectsUndeclaredOrUnusedPlaceholders() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.validateTemplate("select * from t where id={{id}}", null));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.validateTemplate("select * from t", "[{\"name\":\"id\",\"type\":\"number\"}]"));
    }

    @Test
    void rejectsMalformedTypesAndValues() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render("select {{amount}}", "[{\"name\":\"amount\",\"type\":\"number\"}]",
                        Map.of("amount", "1; drop table t")));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.validateTemplate("select {{column}}", "[{\"name\":\"column\",\"type\":\"identifier\"}]"));
    }
}
