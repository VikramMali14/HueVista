package com.gridstore.huevista.paint.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            // Fail soft (column becomes NULL) but never silently: this loses data.
            log.error("Failed to serialize string list to JSON column: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            // Corrupt JSON in the DB — surface it in the logs instead of masking it
            // as an intentionally-empty list.
            log.error("Corrupt JSON in string-list column (returning empty list): {}", e.getMessage());
            return List.of();
        }
    }
}
