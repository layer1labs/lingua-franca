package org.lflang.target.property;

/**
 * The {@code capacities} target property of the Chrono target (spec 002, REQ-105).
 *
 * <p>Value: a single string of comma-separated {@code name=units} pairs, e.g.
 *
 * <pre>{@code
 * target Chrono {
 *   capacities: "storage_bw=1000"
 * }
 * }</pre>
 *
 * <p>Capacities declare the finite resources the blob's admission control accounts against.
 * Every resource named by an operation demand must be declared here, or compilation fails with a
 * precise error (mirroring chronoc's {@code --capacity} flags). LF's target-property machinery
 * has no arbitrary-key integer dictionary type, so the pairs are carried as one string and parsed
 * (and validated) by the ChronoGenerator; that encoding is the documented limitation of this
 * property.
 */
public final class ChronoCapacitiesProperty extends StringProperty {

  /** Singleton target property instance. */
  public static final ChronoCapacitiesProperty INSTANCE = new ChronoCapacitiesProperty();

  private ChronoCapacitiesProperty() {
    super();
  }

  @Override
  public String name() {
    return "capacities";
  }
}
