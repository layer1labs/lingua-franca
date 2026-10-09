package org.lflang.target.property;

import org.lflang.MessageReporter;
import org.lflang.ast.ASTUtils;
import org.lflang.lf.Element;
import org.lflang.target.property.type.BindingProfileType;
import org.lflang.target.property.type.BindingProfileType.BindingProfile;

/**
 * The {@code binding-profile} target property of the Chrono target: {@code hive} (default) or
 * {@code fabric}.
 *
 * <p>The profile is a compile-time validation knob only. It selects the effector manifest the
 * ChronoGenerator checks the program against; it does not change the emitted blob's semantics, does
 * not fork the CSP1 format, and is not recorded in the blob. The blob stays engine-neutral: the
 * same artifact runs on the ChronoHive engine (software effectors) and on the ChronoFabric engine
 * (hardware endpoints), which differ only in effector binding.
 */
public final class BindingProfileProperty
    extends TargetProperty<BindingProfile, BindingProfileType> {

  /** Singleton target property instance. */
  public static final BindingProfileProperty INSTANCE = new BindingProfileProperty();

  private BindingProfileProperty() {
    super(new BindingProfileType());
  }

  @Override
  public BindingProfile initialValue() {
    return BindingProfile.getDefault();
  }

  @Override
  public BindingProfile fromAst(Element node, MessageReporter reporter) {
    var profile = fromString(ASTUtils.elementToSingleString(node), reporter);
    return profile != null ? profile : BindingProfile.getDefault();
  }

  @Override
  protected BindingProfile fromString(String string, MessageReporter reporter) {
    return this.type.forName(string);
  }

  @Override
  public Element toAstElement(BindingProfile value) {
    return ASTUtils.toElement(value.toString());
  }

  @Override
  public String name() {
    return "binding-profile";
  }
}
