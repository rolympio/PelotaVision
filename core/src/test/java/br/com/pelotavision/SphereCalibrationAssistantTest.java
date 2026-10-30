package br.com.pelotavision;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Executável sem JUnit: java br.com.pelotavision.SphereCalibrationAssistantTest
 * Requer o núcleo e as dependências OpenCV disponíveis no classpath.
 */
public final class SphereCalibrationAssistantTest {
    private static void check(boolean ok,String label) {
        if(!ok)throw new AssertionError(label);
    }
    private static PelletDetector.Detection fitted(double x,double y,double a,double b) {
        PelletDetector.Detection seed=new PelletDetector.Detection(x,y,Math.sqrt(a*b),
                Math.PI*a*b,0.98,false,false);
        return new PelletDetector.Detection(seed,x,y,a,b,0,true,0.98,0.1,false,false);
    }
    public static void main(String[] args) {
        PelletDetector.Detection sphere=fitted(20,20,36,36);
        PelletDetector.Detection oval=fitted(60,20,40,32);
        PelletDetector.Detection bad=new PelletDetector.Detection(90,20,30,2800,0.9,false,true);
        PelletDetector.Result all=new PelletDetector.Result(Arrays.asList(oval,sphere,bad),
                200,100,3,0,false);
        List<SphereCalibrationAssistant.Candidate> ranked=
                SphereCalibrationAssistant.suggest(all,200,100,11,10);
        check(ranked.size()==1,"Somente esfera elegível");
        check(ranked.get(0).detectionIndex==1,"Índice preservado");
        check(Math.abs(ranked.get(0).pixelsPerMm-72.0/11.0)<1e-12,
                "Escala em px/mm");
        check(SphereCalibrationAssistant.acceptedIndices(all,null,null,
                new HashSet<>(Arrays.asList(0,2))).equals(Collections.singletonList(1)),
                "Exclusão de detecções");
        check(SphereCalibrationAssistant.acceptedIndices(all,null,null,null).size()==3,
                "Sem exclusões");
        boolean invalid=false;
        try{SphereCalibrationAssistant.suggest(all,200,100,0,2);}
        catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"Diâmetro inválido rejeitado");
        invalid=false;
        try{SphereCalibrationAssistant.acceptedIndices(all,null,null,
                Collections.singleton(9));}
        catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"Índice inválido rejeitado");
        System.out.println("SphereCalibrationAssistantTest: OK");
    }
}
