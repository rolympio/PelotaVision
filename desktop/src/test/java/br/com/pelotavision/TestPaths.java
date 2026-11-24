package br.com.pelotavision;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Caminhos de artefatos gerados pelos testes. A leitura de fixtures continua em
 * {@code pelotavision.validationDir} (verification/), mas toda escrita vai para
 * {@code pelotavision.outputDir} (build/test-artifacts), mantendo verification/ intacto.
 */
final class TestPaths {
    private TestPaths() {}

    static File outputDir() {
        String configured = System.getProperty("pelotavision.outputDir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("pelotavision.outputDir nao configurado");
        }
        return Paths.get(configured).toAbsolutePath().normalize().toFile();
    }

    /** Resolve um subdiretorio de saida e recusa caminhos que escapem ou caiam em verification/. */
    static File artifacts(String relative) {
        Path base = outputDir().toPath();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("artefato fora de outputDir: " + relative);
        }
        String validation = System.getProperty("pelotavision.validationDir");
        if (validation != null && !validation.isBlank()) {
            Path fixtures = Paths.get(validation).toAbsolutePath().normalize();
            if (target.startsWith(fixtures)) {
                throw new IllegalArgumentException("artefato dentro de validationDir: " + target);
            }
        }
        return target.toFile();
    }
}
