package com.flashopify.common.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Writes money as a JSON string with exactly 2 fraction digits ({@code Money} in openapi.yaml). */
public class MoneySerializer extends StdSerializer<BigDecimal> {

  public MoneySerializer() {
    super(BigDecimal.class);
  }

  @Override
  public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider)
      throws IOException {
    gen.writeString(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
  }
}
