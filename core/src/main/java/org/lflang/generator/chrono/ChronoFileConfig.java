package org.lflang.generator.chrono;

import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.emf.ecore.resource.Resource;
import org.lflang.FileConfig;

/**
 * Chrono file configuration. The Chrono target writes only its canonical model and the compiled
 * constraint specification into the source-gen directory, so the default FileConfig layout applies
 * unchanged.
 *
 * @ingroup Generator
 */
public class ChronoFileConfig extends FileConfig {
  public ChronoFileConfig(Resource resource, Path srcGenBasePath, boolean useHierarchicalBin)
      throws IOException {
    super(resource, srcGenBasePath, useHierarchicalBin);
  }
}
