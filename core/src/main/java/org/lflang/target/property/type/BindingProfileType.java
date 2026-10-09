package org.lflang.target.property.type;

import org.lflang.target.property.type.BindingProfileType.BindingProfile;

/**
 * The set of effector binding profiles a Chrono program can be validated against at compile time.
 *
 * <p>A binding profile is <em>not</em> a target and not a language dialect: the Chrono target emits
 * the same engine-neutral CHB1 blob regardless of profile. The profile only names the effector
 * manifest the generator validates the program against (every effector the program needs must exist
 * in the profile, and capacities must be sane for it). Nothing profile-specific is recorded in the
 * blob.
 */
public class BindingProfileType extends OptionsType<BindingProfile> {

  @Override
  protected Class<BindingProfile> enumClass() {
    return BindingProfile.class;
  }

  /** Binding profiles for the Chrono target. */
  public enum BindingProfile {
    /** ChronoHive: software effector callables (the default REQ-103 intrinsic table). */
    HIVE,
    /** ChronoFabric: hardware endpoint effectors (same REQ-103 semantics, hardware binding). */
    FABRIC;

    /** Return the name in lower case. */
    @Override
    public String toString() {
      return this.name().toLowerCase();
    }

    public static BindingProfile getDefault() {
      return BindingProfile.HIVE;
    }
  }
}
