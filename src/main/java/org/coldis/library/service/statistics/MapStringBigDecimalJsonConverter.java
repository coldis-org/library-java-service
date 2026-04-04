package org.coldis.library.service.statistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Converter;
import java.math.BigDecimal;
import java.util.Map;
import org.coldis.library.persistence.converter.AbstractJsonConverter;
import org.coldis.library.serialization.ObjectMapperHelper;

/** Map&lt;String, BigDecimal&gt; from/to JSON converter. */
@Converter(autoApply = true)
public class MapStringBigDecimalJsonConverter
    extends AbstractJsonConverter<Map<String, BigDecimal>> {

  /**
   * @see AbstractJsonConverter#convertToEntityAttribute(ObjectMapper, String)
   */
  @Override
  protected Map<String, BigDecimal> convertToEntityAttribute(
      final ObjectMapper jsonMapper, final String jsonObject) {
    return ObjectMapperHelper.deserialize(
        jsonMapper, jsonObject, new TypeReference<Map<String, BigDecimal>>() {}, false);
  }
}
