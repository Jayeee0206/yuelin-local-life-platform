package com.yuelin.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuelin.dto.Result;
import com.yuelin.dto.ScrollResult;
import com.yuelin.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonConfigTest {
    private static final long UNSAFE_JAVASCRIPT_INTEGER = 9007199254740993L;

    @Test
    void serializesIdentifierLongsExactlyAsStringsButKeepsOtherLongsNumeric() throws Exception {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().identifierLongCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        Shop shop = new Shop()
                .setId(UNSAFE_JAVASCRIPT_INTEGER)
                .setTypeId(UNSAFE_JAVASCRIPT_INTEGER - 2)
                .setAvgPrice(88L);
        JsonNode shopJson = mapper.readTree(mapper.writeValueAsString(shop));

        assertTrue(shopJson.get("id").isTextual());
        assertEquals("9007199254740993", shopJson.get("id").textValue());
        assertTrue(shopJson.get("typeId").isTextual());
        assertTrue(shopJson.get("avgPrice").isIntegralNumber());

        Result page = Result.ok(Collections.emptyList(), 12L);
        assertTrue(mapper.readTree(mapper.writeValueAsString(page)).get("total").isIntegralNumber());
        ScrollResult scroll = new ScrollResult();
        scroll.setMinTime(123456789L);
        assertTrue(mapper.readTree(mapper.writeValueAsString(scroll)).get("minTime").isIntegralNumber());
    }
}
