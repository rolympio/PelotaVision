package br.com.pelotavision;
import static org.junit.Assert.*;
import org.junit.*;
import java.io.*;import java.nio.file.*;import java.util.*;
/** Floresta CANDIDATA (sintética, ml_training/training_synth): o leitor PVF1 do app reproduz os escores do Python. Não é embarcada no APK. */
public class CandidateForestTest {
 @Test public void candidateForestMatchesPythonReferenceScores()throws Exception{
  File dir=new File(new File(System.getProperty("pelotavision.validationDir")).getAbsoluteFile().getParentFile(),"ml_training/training_synth");
  CrackModel model;try(InputStream in=new FileInputStream(new File(dir,"crack_forest_candidate.pvrf"))){model=new CrackModel(in);}
  int n=0;for(String line:Files.readAllLines(new File(dir,"reference_vectors_candidate.csv").toPath())){String[] a=line.split(",");float[] f=new float[72];for(int j=0;j<72;j++)f[j]=Float.parseFloat(a[j]);assertEquals(Double.parseDouble(a[72]),model.score(f),1e-12);n++;}
  assertTrue(n>=200);
 }
}
