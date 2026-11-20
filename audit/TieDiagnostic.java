import java.util.*;
import java.io.*;
import java.nio.file.*;
/** Post-hoc tie-rule sensitivity for the single pre-specified headline condition. No policy uses the test draws. */
public class TieDiagnostic {
 public static void main(String[] args) throws Exception {
  var model=new UAVRelaySim(); var cond=new PlacementMethods.Condition("heavy","weak");
  try(var w=Files.newBufferedWriter(Path.of(args[0]))) {
   w.write("cohort,seed,method,tie,number_of_primary_maximizers,x,y,pooled_bps,worst_bps,outage\n");
   for (int h=0;h<2;h++) for(int j=0;j<20;j++) {
    int seed=2026111600+h*4000+j; var est=model.generateEstimatedUsers("main",new Random(seed));
    var sel=model.sampleUserPositions(est,100,200,new Random(seed+100000));
    var methods=new PlacementMethods(model,est,model.gridPoints);var sc=methods.scoreAccess(sel,"heavy");
    var trueN=methods.nominal(cond);var trueR=methods.select(sc,cond); int Q=model.gridPoints.length;
    var ev=model.sampleUserPositions(est,100,1000,new Random(seed+200000));
    for (int m=0;m<2;m++) {
     double[] a=new double[Q],b=new double[Q];for(int q=0;q<Q;q++){
      var rate=model.computeRates(model.gridPoints[q],est,"heavy","weak"); a[q]=(m==0?Arrays.stream(rate.rAcc).min().orElseThrow():sc.worstAccessQ05()[q]); b[q]=Math.min(a[q],rate.rBh[0]);
     }
     double max=Arrays.stream(b).max().orElseThrow();int first=-1,best=-1,n=0;for(int q=0;q<Q;q++) if(b[q]==max){n++;if(first<0)first=q;if(best<0||a[q]>a[best])best=q;}
     if(!Arrays.equals(model.gridPoints[first],(m==0?trueN:trueR.robust()).placement().bestPos)||!((m==0?trueN:trueR.robust()).placement().bestObj==max))throw new IllegalStateException("published rule");
     if(m==1&&!Arrays.equals(model.gridPoints[best],trueR.access().placement().bestPos))throw new IllegalStateException("constant cap identity");
     for(int t=0;t<2;t++){var pt=model.gridPoints[t==0?first:best];var z=EvaluationMetrics.evaluate(model,new EvaluationMetrics.TestCase(pt,ev,cond));w.write(String.format(Locale.ROOT,"%s,%d,%s,%s,%d,%.0f,%.0f,%.12g,%.12g,%.12g%n",h==0?"archived":"fresh",seed,m==0?"N":"R",t==0?"grid":"margin",n,pt[0],pt[1],z.networkQ05(),z.worstUserQ05(),z.outage()));}
    }
   }
  }
 }
}
