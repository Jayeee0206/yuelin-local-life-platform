package com.yuelin.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** Keep identifiers exact in JavaScript without changing counters, prices, or cursors. */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer identifierLongCustomizer() {
        return builder -> {
            IdentifierLongSerializer serializer = new IdentifierLongSerializer(false);
            builder.serializerByType(Long.class, serializer);
            builder.serializerByType(Long.TYPE, serializer);
        };
    }

    static final class IdentifierLongSerializer extends StdSerializer<Long> implements ContextualSerializer {
        private final boolean writeAsString;

        IdentifierLongSerializer(boolean writeAsString) {
            super(Long.class);
            this.writeAsString = writeAsString;
        }

        @Override
        public void serialize(Long value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (writeAsString) {
                generator.writeString(Long.toString(value));
            } else {
                generator.writeNumber(value);
            }
        }

        @Override
        public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property)
                throws JsonMappingException {
            if (property == null) {
                return this;
            }
            String name = property.getName();
            boolean identifier = "id".equals(name) || name.endsWith("Id") || name.endsWith("Ids");
            return identifier == writeAsString ? this : new IdentifierLongSerializer(identifier);
        }
    }
}
