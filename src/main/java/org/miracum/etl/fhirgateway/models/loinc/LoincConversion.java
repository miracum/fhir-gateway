package org.miracum.etl.fhirgateway.models.loinc;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoincConversion {

  @JsonProperty("loinc")
  private String loinc;

  @JsonProperty("unit")
  private String unit;

  @JsonProperty("value")
  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private BigDecimal value;

  @JsonProperty("id")
  private String id;

  @JsonProperty("display")
  private String display;

  @JsonProperty("loinc")
  public String getLoinc() {
    return loinc;
  }

  @JsonProperty("loinc")
  public LoincConversion setLoinc(String loinc) {
    this.loinc = loinc;
    return this;
  }

  @JsonProperty("unit")
  public String getUnit() {
    return unit;
  }

  @JsonProperty("unit")
  public LoincConversion setUnit(String unit) {
    this.unit = unit;
    return this;
  }

  @JsonProperty("value")
  public BigDecimal getValue() {
    return value;
  }

  @JsonProperty("value")
  public LoincConversion setValue(BigDecimal value) {
    this.value = value;
    return this;
  }

  @JsonProperty("id")
  public String getId() {
    return id;
  }

  @JsonProperty("id")
  public LoincConversion setId(String id) {
    this.id = id;
    return this;
  }

  @JsonProperty("display")
  public String getDisplay() {
    return display;
  }

  @JsonProperty("display")
  public LoincConversion setDisplay(String display) {
    this.display = display;
    return this;
  }

  @Override
  public String toString() {
    return "LoincConversion{"
        + "loinc='"
        + loinc
        + '\''
        + ", unit='"
        + unit
        + '\''
        + ", value="
        + value
        + ", id='"
        + id
        + '\''
        + '}';
  }
}
