package br.com.pelotavision;
import java.io.*;
/** Portable inference of the bundled scikit-learn forest. Scores are not calibrated probabilities. */
public final class CrackModel {
 public static final String ID="random_forest_20261027_v2";
 public static final int FEATURES=72;
 private static final class Tree {int[] f,l,r;double[] threshold,p;Tree(int n){f=new int[n];l=new int[n];r=new int[n];threshold=new double[n];p=new double[n];}}
 private final Tree[] trees;
 public CrackModel(InputStream input)throws IOException{
  DataInputStream in=new DataInputStream(new BufferedInputStream(input));
  if(in.readInt()!=0x50564631||in.readInt()!=1||in.readInt()!=FEATURES)throw new IOException("Modelo de trincas incompatível");
  int n=in.readInt();if(n<1||n>1024)throw new IOException("Floresta inválida");trees=new Tree[n];
  for(int j=0;j<n;j++){
   int size=in.readInt();if(size<1||size>65535)throw new IOException("Árvore inválida");Tree t=trees[j]=new Tree(size);
   for(int i=0;i<size;i++){
    t.f[i]=in.readInt();t.l[i]=in.readInt();t.r[i]=in.readInt();t.threshold[i]=in.readDouble();t.p[i]=in.readDouble();
    if(!Double.isFinite(t.threshold[i])||!Double.isFinite(t.p[i])||t.p[i]<0||t.p[i]>1)throw new IOException("Nó inválido");
    if(t.f[i]==-2){if(t.l[i]!=-1||t.r[i]!=-1)throw new IOException("Folha inválida");}
    else if(t.f[i]<0||t.f[i]>=FEATURES||t.l[i]<=i||t.r[i]<=i||t.l[i]>=size||t.r[i]>=size)throw new IOException("Ramo inválido");
   }
  }
  if(in.read()!=-1)throw new IOException("Dados excedentes no modelo");
 }
 public double score(float[] features){
  if(features.length!=FEATURES)throw new IllegalArgumentException("72 atributos necessários");
  for(float v:features)if(!Float.isFinite(v))throw new IllegalArgumentException("Atributo inválido");
  double sum=0;for(Tree t:trees){int i=0;while(t.f[i]!=-2)i=features[t.f[i]]<=t.threshold[i]?t.l[i]:t.r[i];sum+=t.p[i];}return sum/trees.length;
 }
}
