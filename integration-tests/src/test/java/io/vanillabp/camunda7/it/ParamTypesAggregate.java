package io.vanillabp.camunda7.it;

import java.math.BigDecimal;
import java.math.BigInteger;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The aggregate of the BPMN process <code>ParamTypes</code>. It holds the five values
 * which decide what a <code>&#64;TaskParam</code> can be declared as: a decimal with a
 * trailing zero, a whole number above what a double holds exactly, a float whose double
 * form is a number nobody typed, a long above <code>Integer.MAX_VALUE</code> and the same
 * decimal one level down.
 * <p>
 * The columns have no public getters and every shared value has one, so what reaches the
 * engine is exactly those five.
 */
@Entity
@Table(name = "C7_PARAM_TYPES_AGGREGATE")
public class ParamTypesAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7ParamTypesSeq")
  @SequenceGenerator(name = "c7ParamTypesSeq", initialValue = 820000, allocationSize = 1)
  private Long id;

  BigDecimal totalValue;

  String hugeText;

  Float rateValue;

  Long countValue;

  public Long getId() {

    return id;

  }

  /**
   * @return A top-level decimal, which is an object variable keeping its class
   */
  public BigDecimal getTotal() {

    return totalValue;

  }

  /**
   * @return A whole number above 2^53, the value a detour through a double would break
   */
  @Transient
  public BigInteger getHuge() {

    return new BigInteger(hugeText);

  }

  /**
   * @return A float whose double form is a number nobody typed
   */
  public Float getRate() {

    return rateValue;

  }

  /**
   * @return A long above Integer.MAX_VALUE, which Camunda 7 has a scalar type for
   */
  public Long getCount() {

    return countValue;

  }

  /**
   * @return The same decimal one level down, inside the map the sync model builds
   */
  @Transient
  public ParamTypesOrder getOrder() {

    return new ParamTypesOrder(totalValue);

  }

  public void fillWithTheSample() {

    totalValue = new BigDecimal("120.50");
    hugeText = "9007199254740993";
    rateValue = Float.valueOf(0.1f);
    countValue = Long.valueOf(3000000000L);

  }

}
