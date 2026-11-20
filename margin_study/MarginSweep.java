import java.util.*;
import java.io.*;
import java.nio.file.*;
/** Factorial extension. Compile against original Java model. Output paths are explicitly supplied. */
public class MarginSweep {
 public static void main(String[] args) throws Exception {
  var root=Path.of(args[0]);Files.createDirectories(root);var model=new UAVRelaySim(); var grid=model.gridPoints;
  try(var w=Files.newBufferedWriter(root.resolve("margin_sweep.csv"));var mp=Files.newBufferedWriter(root.resolve("example_map.csv"))){
   w.write("cohort,seed,error_m,shadow,backhaul,method,tie,nmax,x,y,objective_bps,access_score_bps,pooled_bps,worst_bps,outage,backhaul_fraction,mean_bps\n");mp.write("x,y,n_access_bps,r_access_bps,n_primary_bps,r_primary_bps\n");
   for(int h=0;h<2;h++)for(int j=0;j<20;j++){
    int seed=2026111600+4000*h+j;var est=model.generateEstimatedUsers("main",new Random(seed));var methods=new PlacementMethods(model,est,grid);
    for(int e:new int[]{0,10,25,50,100}){var sel=model.sampleUserPositions(est,e,200,new Random(seed+100000));var test=model.sampleUserPositions(est,e,1000,new Random(seed+200000));
    for(String sh:new String[]{"light","heavy"}){var sc=methods.scoreAccess(sel,sh);for(String b:new String[]{"weak","medium","strong"}){
     var cond=new PlacementMethods.Condition(sh,b); var en=methods.nominal(cond);var er=methods.select(sc,cond);double[][] acc=new double[2][grid.length],v=new double[2][grid.length];
     for(int i=0;i<grid.length;i++){var rate=model.computeRates(grid[i],est,sh,b);acc[0][i]=Arrays.stream(rate.rAcc).min().orElseThrow();acc[1][i]=sc.worstAccessQ05()[i];for(int m=0;m<2;m++)v[m][i]=Math.min(acc[m][i],rate.rBh[0]);if(h==0&&j==0&&e==100&&sh.equals("heavy")&&b.equals("weak"))mp.write(String.format(Locale.ROOT,"%.0f,%.0f,%.12g,%.12g,%.12g,%.12g%n",grid[i][0],grid[i][1],acc[0][i],acc[1][i],v[0][i],v[1][i]));}
     for(int m=0;m<2;m++){double max=Arrays.stream(v[m]).max().orElseThrow();int first=-1,best=-1,n=0;for(int i=0;i<grid.length;i++)if(v[m][i]==max){if(first<0)first=i;if(best<0||acc[m][i]>acc[m][best])best=i;n++;}
      var control=(m==0?en:er.robust()).placement();if(max!=control.bestObj||!Arrays.equals(control.bestPos,grid[first]))throw new Error("control mismatch");
      for(int t=0;t<2;t++){int i=t==0?first:best;var z=EvaluationMetrics.evaluate(model,new EvaluationMetrics.TestCase(grid[i],test,cond));w.write(String.format(Locale.ROOT,"%s,%d,%d,%s,%s,%s,%s,%d,%.0f,%.0f,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g%n",h==0?"original":"second",seed,e,sh,b,m==0?"N":"R",t==0?"grid":"margin",n,grid[i][0],grid[i][1],max,acc[m][i],z.networkQ05(),z.worstUserQ05(),z.outage(),z.backhaulFraction(),z.mean()));}
     }
    }}}
   }
  }
 }
}
