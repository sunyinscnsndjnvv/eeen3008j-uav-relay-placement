from pathlib import Path
import csv, math, statistics as s, collections as co
from reportlab.pdfgen.canvas import Canvas
from reportlab.lib import colors

# Run from the archive root: python review_evidence.py results OUTPUT_DIR
import sys
src=Path(sys.argv[1]); dest=Path(sys.argv[2]); dest.mkdir(parents=True,exist_ok=True)
rows=list(csv.DictReader((src/'metrics.csv').open())); raw=list(csv.DictReader((src/'representative_rates.csv').open())); data={}
for x in rows:
 if x['study']=='core':data.setdefault((int(x['test_epsilon']),x['shadow'],x['backhaul'],x['seed']),{})[x['method']]=x
assert len(data)==600 and all(set(v)==set('NRA') for v in data.values())
def group(e,sh,b):return [g for k,g in sorted(data.items()) if k[:3]==(e,sh,b)]
def vals(g,m,k,fac):return [float(x[m][k])*fac for x in g]
def stats(g,k,f):
 n,r=[vals(g,m,k,f) for m in 'NR']; d=[y-x for x,y in zip(n,r)]; h=2.0930240544*s.stdev(d)/math.sqrt(len(d));return s.mean(n),s.mean(r),s.mean(d),s.mean(d)-h,s.mean(d)+h
T='';
def text(t):
 global T; T+=t+'\n'
def tab(head,lines,layout):
 text(r'\begin{center}\renewcommand{\arraystretch}{1.17}\begin{tabular}{'+layout+'}\\toprule\n'+head+r'\\\midrule');
 for l in lines:text(' & '.join(str(x) for x in l)+r'\\');
 text(r'\bottomrule\end{tabular}\end{center}')
def nn(x,d=3):return f'{x:.{d}f}' if abs(x)>=0.5*10**(-d) else f'{0:.{d}f}'
def chart(filename, panels):
 c=Canvas(str(dest/filename),pagesize=(494,205)); ink='#244256';blue='#21677a';gold='#a45127';
 for idx,(g,col,k,fac,title,unit) in enumerate(panels):
  x0=44+idx*249;y0=41;w=182;h=125; d=[(float(v['R'][k])-float(v['N'][k]))*fac for v in g];lo=min(0,min(d));hi=max(0,max(d)); span=hi-lo or 1; lo-=.07*span;hi+=.07*span
  ps=lambda v:y0+(v-lo)*h/(hi-lo); px=lambda i:x0+i*w/19
  c.setFillColor(colors.HexColor(ink));c.setFont('Helvetica-Bold',10);c.drawString(x0-21,188,title);c.setFont('Helvetica',8);c.drawString(x0-21,174,unit)
  for y in [min(d),0,max(d)]:
   c.setStrokeColor(colors.HexColor('#d0d9dd'));c.line(x0,ps(y),x0+w,ps(y));c.setFillColor(colors.HexColor(ink));c.setFont('Helvetica',7.5)
   if y==0 or abs(ps(y)-ps(0))>10:c.drawRightString(x0-4,ps(y)-2,f'{y:.3f}')
  for i,z in enumerate(d):
   c.setStrokeColor(colors.HexColor(col));c.setFillColor(colors.HexColor(col));c.line(px(i),ps(0),px(i),ps(z));c.circle(px(i),ps(z),2.1,stroke=0,fill=1)
  c.setFillColor(colors.HexColor(ink));c.setFont('Helvetica',8)
  for i in [0,4,9,14,19]:c.drawCentredString(px(i),y0-12,str(i+1));
  c.drawCentredString(x0+w/2,10,'Layout (ordered by seed)')
 c.save()
chart('paired_layout_evidence.pdf',[(group(100,'heavy','weak'),'#21677a','network_q05_bps',1e-6,'(a) 100 m, heavy, weak','R minus N: network 5th percentile (Mbps)'),(group(50,'heavy','medium'),'#a45127','network_q05_bps',1e-6,'(b) 50 m, heavy, medium','R minus N: network 5th percentile (Mbps)')]);
text(r'''\section{Core results by operating condition}\label{app:core}
Each row summarizes 20 matched layouts. The first pair of columns reports the mean of the held-out, within-layout network fifth percentile (Mbps). The next pair reports the mean percentage of user--realization rates below 0.5 Mbps. The final columns show the paired R-minus-N change and its descriptive 95\% Student interval for the network percentile. The 20 layout seeds recur across rows. An interval printed as zero at both ends can be checked at full precision in the archived data.''')
for sh in ['light','heavy']:
 text(r'\subsection{'+sh.capitalize()+' access blockage}');ls=[]
 for b in ['weak','medium','strong']:
  for e in [0,10,25,50,100]:
   g=group(e,sh,b); a=stats(g,'network_q05_bps',1e-6);o=stats(g,'outage_fraction',100);ls.append([b,e,nn(a[0]),nn(a[1]),nn(o[0],2),nn(o[1],2),nn(a[2],4),'['+nn(a[3],4)+', '+nn(a[4],4)+']'])
 tab(r'Cap & Error (m) & $Q_N$ & $Q_R$ & Out. N (\%) & Out. R (\%) & $\Delta Q$ & 95\% interval',ls,'lrrrrrrr')
text(r'''Small negative differences in heavy blockage under medium and strong backhaul refer to the pooled percentile. They should not be interpreted as losses for every individual user or layout. In the weak regime many outcomes equal the threshold, so the continuous rate change and the percentage of threshold crossings convey different information.''')
text(r'''\clearpage\section{The least-served user and the pooled network tail}\label{app:user}
For a fixed layout, the pooled percentile gives every realization from every user equal weight. The worst-user statistic first looks at each individual user's distribution. With ten users and 1,000 test draws these are, respectively, the 500th of 10,000 pooled rates and the smallest of ten individual 50th order statistics. Selection by R uses yet another data set: the tenth observation among 200 selection draws for each user. The stored test data can therefore answer whether the objective transfers to held-out locations without reusing the scoring samples.
\subsection{Do the two measures tell the same story?}
Entries in the table are Mbps. The paired intervals below summarize differences between methods within the same 20 layouts; the weak and medium cases also appear as individual observations in the figure.''')
ls=[]
for e,b in [(100,'weak'),(50,'medium'),(100,'medium'),(100,'strong')]:
 for k,label in [('network_q05_bps','Pooled'),('worst_user_q05_bps','Worst user')]:
  a=stats(group(e,'heavy',b),k,1e-6);ls.append([e,b,label,*[nn(z,4) for z in a[:3]],'['+nn(a[3],4)+', '+nn(a[4],4)+']'])
tab('Error (m) & Cap & Statistic & N & R & R--N & 95\\% interval',ls,'rllrrrr')
text(r'''Under the weak cap with 100 m error, the gain for the least-served user is larger than the gain in the pooled statistic. For 100 m and medium or strong backhaul, the signs of the sample mean changes differ between statistics; the paired intervals for both include zero. Even when the physical model is unchanged, a recommendation for a named user cannot be based on a pooled percentile alone.
\begin{center}\includegraphics[width=.92\linewidth]{figures/paired_layout_evidence.pdf}\end{center}
\noindent\textit{Paired layout differences.} The dot for a layout compares R and N under exactly the same evaluation draws. The vertical scales differ between panels. A site held fixed by both rules yields identical paired outcomes; separate method confidence intervals would obscure that dependence.
\subsection{How a strong-looking result can depend on a threshold}
The weak backhaul allocation is exactly 0.5 Mbps per user. Under a ``strictly below'' definition, a user at this ceiling passes; under ``at or below,'' every user fails. The weak-backhaul outage result therefore measures the fraction of links that cannot maintain their share of the available transport. It does not say that 98\% of test outcomes can exceed 0.5 Mbps. For a target greater than 0.5 Mbps, changing the UAV position alone cannot produce service in this regime.''')
text(r'''\clearpage\section{Reconstructing a placement decision from the archive}\label{app:example}
\subsection{One layout under the weak cap}
Take layout seed 2026111600, 100 m actual and assumed error, heavy blockage, and weak backhaul. The indices in the last column count user--realization outcomes below 0.5 Mbps out of 10,000. Scores for N and R are based on different information; A's selection score uses the access hop only. The held-out metrics use both hops for all three methods.''')
g=group(100,'heavy','weak')[0];ls=[]
for m in 'NRA':
 v=g[m];ls.append([m,'(%d, %d)'%(float(v['x']),float(v['y'])),*[nn(float(v[k])*1e-6,4) for k in ['objective_bps','network_q05_bps','worst_user_q05_bps']],round(float(v['outage_fraction'])*10000)])
tab('Method & Site (m) & Selection score & Test pooled & Test worst user & Failures',ls,'llrrrr')
text(r'''N and R both attain the 0.5 Mbps selection ceiling, but at different grid points. N reaches its ceiling using reported positions; the error samples used by R disqualify some nominally acceptable sites. The two robust methods reach the same held-out network percentile at different positions. R still has a positive failure count. A fifth percentile at the ceiling does not imply zero failures: fewer than five percent of observations can sit below it.
\subsection{From individual test outcomes to the metrics}
The archive also retains all per-user outcomes for the first seed in a different condition: 50 m error, heavy blockage and medium backhaul. Here N, R and A choose the same position, $(375,325)$ m. Their test outcomes coincide. The table below follows the delivered-rate column, in Mbps, in those saved rows. User IDs 0--2 in the code are users 1--3 in the text. Each row uses exactly 1,000 test outcomes.''')
rawN=[x for x in raw if x['method']=='N'];ls=[]
for i in range(10):
 z=[v for v in rawN if int(v['user'])==i];dr=sorted(float(x['delivered_bps'])/1e6 for x in z); assert len(z)==1000;ls.append([i+1,'yes' if i<3 else 'no',nn(dr[49],4),nn(s.mean(dr),4),sum(x<.5 for x in dr),sum(x==1.75 for x in dr)])
tab(r'User & Blocked & 5th percentile & Mean & Below 0.5 & At 1.75',ls,'rlrrrr')
text(r'''On the first draw, user 1 receives 1.356328 Mbps on access and 1.75 Mbps on backhaul, hence 1.356328 Mbps delivered. Across all users, 7,000 of the 10,000 rates equal the 1.75 Mbps ceiling. No outcome is below 0.5 Mbps, yet 11.06\% are below 1.0 Mbps. This second threshold is a diagnostic for this one layout only. The frozen threshold for method comparisons remains 0.5 Mbps. The pooled fifth percentile for the layout is 0.904679 Mbps; the worst individual fifth percentile is 0.812869 Mbps. No averaging over the ten individual percentiles recovers the pooled order statistic in general.''')
text(r'''\clearpage\section{Sensitivity, physical limits and reproducibility}\label{app:sens}
\subsection{Changing a single part of the experiment}
The table reports held-out metrics for R. Each mean uses 20 layouts. The reference condition is a 50 m assumed and actual radius, main layout, heavy blockage, medium cap, 200 selection samples and a $21\times21$ grid. Rows below it compare to this reference when only one input changes. The uniform weak/light row changes more than one input and is labelled explicitly. The repeated reference within the mismatch study is omitted.''')
ls=[]; cases=[('Reference','core','main','50','medium','heavy','21','200'),('Actual error 25 m','mismatch','main','25','medium','heavy','21','200'),('Actual error 100 m','mismatch','main','100','medium','heavy','21','200'),('Grid 41 by 41','grid','main','50','medium','heavy','41','200')]+[(f'Selection draws {j}','stability','main','50','medium','heavy','21',str(j)) for j in [100,400,800]]+[(f'Uniform, {b}/{sh}','uniform','uniform','50',b,sh,'21','200') for b,sh in [('weak','light'),('medium','heavy'),('strong','heavy')]]
for lab,sy,sc,ep,b,sh,gr,se in cases:
 sub=[x for x in rows if tuple(x[k] for k in ['method','study','scenario','test_epsilon','backhaul','shadow','grid_side','selection_samples'])==('R',sy,sc,ep,b,sh,gr,se)];assert len(sub)==20
 ls.append([lab,*[nn(s.mean(float(x[k]) for x in sub)*f,d) for k,f,d in [('network_q05_bps',1e-6,4),('worst_user_q05_bps',1e-6,4),('outage_fraction',100,2),('mean_bps',1e-6,4)]]])
tab('Change & Network 5th & Worst user 5th & Outage (\\%) & Mean rate',ls,'lrrrr')
text(r'''Rate columns are in Mbps. More grid points can improve the maximized sample score because the coarse grid is a subset of the fine grid. That mathematical property does not apply to a fresh test percentile, and adding more selection draws also changes the empirical objective. The mismatched-radius results measure the cost of evaluation under a different distribution at a fixed assumed radius; they do not compare estimators of the error radius.
\subsection{Which resource can a position change?}
The farthest grid corners from the base station have a three-dimensional separation of $\sqrt{450^2+250^2+75^2}=520.22$ m (rounded). At this distance the link budget yields about 90.5 Mbps raw backhaul. The 10 and 35 Mbps caps therefore bind everywhere, independently of the candidate position. They leave 0.50 and 1.75 Mbps per user after time sharing. Under the 100 Mbps cap the position can affect the raw rate. A change of cap can still reclassify many outcomes without moving the network's lower tail. In this study a different position under a flat cap can arise from tie resolution rather than a preference for a stronger radio path to the base station.
\subsection{How to check the evidence}
The archive retains 1,800 core method records and 600 supplementary records. A record is keyed by study, scenario, both radii, blockage, backhaul regime, selection sample count, grid size, layout seed and method. For a contrast, match on all of these except method. To check units, divide rate fields in bit/s by $10^6$ and multiply fractions by 100 for percentages. To check pairing, verify a single row for each of N, R and A under the remaining key. To reconstruct the individual example, filter the representative-rate file by method and order its 10,000 delivered rates. Selection and evaluation times are excluded from numerical reproduction because they depend on the machine. The source package contains the calculations for the additional tables and paired-layout plot.''')
(dest/'audit_tables.tex').write_text(T,encoding='utf-8')
