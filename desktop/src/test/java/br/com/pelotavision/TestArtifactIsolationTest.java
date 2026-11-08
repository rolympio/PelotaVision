package br.com.pelotavision;

import static org.junit.Assert.*;

import java.io.File;
import org.junit.Test;

/**
 * Regressao de 0.7.0: os testes gravavam PDF/PNG dentro de verification/, que e o diretorio
 * de fixtures versionado. A saida deve ficar em build/test-artifacts e nunca em verification/.
 */
public class TestArtifactIsolationTest {
    @Test public void outputDirIsBuildScoped() {
        File out = TestPaths.outputDir();
        assertTrue("outputDir deve ser absoluto: " + out, out.isAbsolute());
        assertEquals("test-artifacts", out.getName());
        assertEquals("build", out.getParentFile().getName());
    }

    @Test public void outputDirIsSeparateFromFixtures() {
        String validation = System.getProperty("pelotavision.validationDir");
        assertNotNull("validationDir (fixtures) deve estar configurado", validation);
        File fixtures = new File(validation).getAbsoluteFile();
        File out = TestPaths.outputDir();
        assertNotEquals(fixtures, out);
        assertFalse("build/test-artifacts nao pode ficar dentro de verification/",
                out.toPath().startsWith(fixtures.toPath()));
    }

    @Test public void knownArtifactsResolveUnderOutputDir() {
        File out = TestPaths.outputDir();
        assertEquals(new File(out, "pdf-windows-0.5.2"), TestPaths.artifacts("pdf-windows-0.5.2"));
        assertEquals(new File(out, "visuals-0.14.0"), TestPaths.artifacts("visuals-0.14.0"));
        assertEquals(new File(out, "rosin-rammler-windows"), TestPaths.artifacts("rosin-rammler-windows"));
    }

    @Test public void relativeEscapeIsRejected() {
        try {
            TestPaths.artifacts("../verification/pdf-windows-0.5.2");
            fail("caminho com .. deveria ser recusado");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("fora de outputDir"));
        }
    }
}
