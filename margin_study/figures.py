from pathlib import Path
import csv, sys, statistics as st, math, numpy as np
import matplotlib;matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.colors import ListedColormap
p=Path(sys.argv[1]);core=Path(sys.argv[2]);dest=Path(sys.argv[3]);dest.mkdir(exist_ok=True,parents=True)
def read(p):return list(csv.DictReader(p.open(encoding='utf-8-sig')))
rows=read(p/'margin_sweep.csv');assert len(rows)==4800
D={(x['cohort'],int(x['seed']),int(x['error_m']),x['shadow'],x['backhaul'],x['method'],x['tie']):x for x in rows};assert len(D)==4800
orig=[x for x in read(core/'metrics.csv') if x['study']=='core' and x['method'] in ['N','R']];assert len(orig)==1200
for x in orig:
 y=D['original',int(x['seed']),int(x['test_epsilon']),x['shadow'],x['backhaul'],x['method'],'grid']; assert all(float(x[a])==float(y[b]) for a,b in [('x','x'),('y','y'),('objective_bps','objective_bps'),('outage_fraction','outage'),('network_q05_bps','pooled_bps'),('worst_user_q05_bps','worst_bps'),('mean_bps','mean_bps'),('backhaul_fraction','backhaul_fraction')]);
for k,x in D.items():
 if k[-1]=='margin':
  z=D[k[:-1]+('grid',)];assert x['objective_bps']==z['objective_bps']; assert float(x['access_score_bps'])>=float(z['access_score_bps'])
def vec(h,e,sh,b,m,t,k='outage',scale=100):
 start=2026111600+(4000 if h=='second' else 0);return np.array([float(D[h,i,e,sh,b,m,t][k])*scale for i in range(start,start+20)])
def ci(x):return x.mean(),2.0930240544*x.std(ddof=1)/np.sqrt(len(x))
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':9,'axes.spines.top':False,'axes.spines.right':False,'axes.titlelocation':'left','axes.labelcolor':'#24394b','xtick.color':'#24394b','ytick.color':'#24394b','pdf.fonttype':42,'axes.axisbelow':True}); cs=['#a55024','#006a71'];bg='#c5d6d6'
def save(f,n):f.savefig(dest/n,bbox_inches='tight',pad_inches=.07);plt.close(f)
f,ax=plt.subplots(1,2,figsize=(7.4,2.45),sharey=True,layout='constrained')
for j,(a,h) in enumerate(zip(ax,['original','second'])):
 for i,m in enumerate('NR'):
  A=[vec(h,100,'heavy','weak',m,t) for t in ['grid','margin']];x=np.arange(2)+(i-.5)*.08;y=[ci(v)[0] for v in A];a.plot(x,y,marker='os'[i],color=cs[i],label=['Nominal positions (N)','Sampled positions (R)'][i],lw=1.7);a.errorbar(x,y,yerr=[ci(v)[1] for v in A],fmt='none',ecolor=cs[i],capsize=3);a.text(x[0]+(.04 if i==0 else -.04),y[0]+(.65 if i==0 else .8),f'{y[0]:.2f}%',color=cs[i],ha='left');
 a.set(xticks=[0,1],xticklabels=['Grid order','Access margin'],xlim=(-.25,1.35),ylim=(0,15));a.set_title(['(a) Original 20 layouts','(b) Second 20 layouts'][j],fontsize=10,weight='bold');a.grid(axis='y',alpha=.18);a.text(.99,.34,'N / R: '+('1.53 / 1.55%' if j==0 else '1.53 / 1.54%'),ha='right',transform=a.transAxes,fontsize=8);a.set_xlabel('Choice among tied sites');
ax[0].set_ylabel('Outage (%)');ax[1].legend(loc='upper right',fontsize=7,frameon=False);save(f,'service.pdf')
mp=read(p/'example_map.csv'); ge=[x for x in read(core/'geometry.csv') if x['kind']=='user'];f,axes=plt.subplots(1,2,figsize=(7.1,3.3),layout='constrained');
for ax,key,title,first in zip(axes,['n','r'],['(a) Reported positions: 95 top sites','(b) Sampled positions: 14 top sites'],[(250,200),(300,275)]):
 def xy(v):return [float(q['x']) for q in v],[float(q['y']) for q in v]
 ax.scatter(*xy(mp),marker='.',color='#c5ced0',s=5);good=[q for q in mp if float(q[key+'_primary_bps'])==500000];ax.scatter(*xy(good),marker='s',color='#79b7ad',s=14,label='Sites scoring 0.5');ax.scatter(*xy(ge[:3]),marker='^',facecolors='#bc542d',edgecolors='white',lw=.4,s=34,label='Obstructed user');ax.scatter(*first,marker='D',facecolors='none',edgecolors='black',lw=1.2,s=56,label='First top site');ax.scatter(375,325,marker='*',color='#402052',s=100,label='Margin choice');ax.scatter(50,250,marker='s',s=15,color='#263d56');ax.text(48,215,'BS',fontsize=7);ax.set(aspect='equal',xlim=(-15,515),ylim=(-15,515),xticks=[0,100,200,300,400,500],yticks=[0,100,200,300,400,500],xlabel='East (m)');ax.set_title(title,fontsize=9.5,weight='bold');
axes[0].set_ylabel('North (m)');h,l=axes[0].get_legend_handles_labels();f.legend(h,l,loc='outside lower center',ncol=4,frameon=False,fontsize=8);save(f,'plateau.pdf')
f,axes=plt.subplots(1,2,figsize=(7.15,2.5),layout='constrained');es=[0,10,25,50,100]
for ax,m in zip(axes,'NR'):
 for j,(h,col,mark) in enumerate(zip(['original','second'],cs,['o','s'])):
  ds=[ci(vec(h,e,'heavy','weak',m,'grid')-vec(h,e,'heavy','weak',m,'margin')) for e in es];ax.errorbar(np.array(es)+(-.8 if j==0 else .8),[d[0] for d in ds],yerr=[d[1] for d in ds],color=col,lw=1.3,marker=mark,ms=3,capsize=2,label=['Original 20','Second 20'][j]);
 ax.set_xlabel('Position-error radius (m)');ax.set_xticks(es);ax.set_title(('(a) Nominal (N)' if m=='N' else '(b) Sampled (R)'),size=10,weight='bold');ax.grid(axis='y',alpha=.22);ax.axhline(0,color='#788488',lw=.5)
axes[0].set_ylabel('Outage reduction (pp)');axes[1].legend(frameon=False,fontsize=7);save(f,'error_margin.pdf')
f,ax=plt.subplots(1,2,figsize=(7.15,2.55),layout='constrained');R=[v for v in read(core/'metrics.csv') if v['study']=='core' and v['method']=='R' and v['shadow']=='heavy' and v['test_epsilon']=='100'];labels=['Weak','Medium','Strong'];
for j,b in enumerate(['weak','medium','strong']):
 rr=[x for x in R if x['backhaul']==b];assert len(rr)==20;bshare=st.mean(float(x['backhaul_fraction'])*100 for x in rr);ashare=st.mean(float(x['access_fraction'])*100 for x in rr);ax[0].bar(j,bshare,color='#316b82',width=.56);ax[0].bar(j,ashare,bottom=bshare,color='#c9dcd7',width=.56);ax[0].text(j,bshare/2,f'{bshare:.0f}%',ha='center',va='center',color='white');
for col,metric,lab,mk in [('#006a71','network_q05_bps','Network fifth percentile','o'),('#a55024','mean_bps','Mean delivered rate','s')]:
 ax[1].plot(np.arange(3),[st.mean(float(v[metric])/1e6 for v in R if v['backhaul']==b) for b in ['weak','medium','strong']],marker=mk,lw=1.7,color=col,label=lab)
ax[0].set_ylabel('Outcomes (%)');ax[0].set_title('(a) Dark share: backhaul binds',fontsize=10,weight='bold');ax[1].set_ylabel('Delivered rate (Mbps)');ax[1].set_title('(b) Mean and lower tail',fontsize=10,weight='bold');ax[1].legend(fontsize=7,frameon=False);ax[1].set_ylim(0,4.3)
for a in ax:a.set_xticks(np.arange(3),labels);a.set_xlabel('Backhaul regime')
save(f,'bottleneck.pdf')
# Pairwise estimates for both cohorts and all 30 conditions.
with (p/'contrasts.csv').open('w',newline='') as f:
 w=csv.writer(f);w.writerow(['cohort','error_m','shadow','backhaul','metric','unit','left','right','left_minus_right','ci_low','ci_high']);
 for h in ['original','second']:
  for e in es:
   for sh in ['light','heavy']:
    for b in ['weak','medium','strong']:
     for metric,unit,fac in [('outage','percentage points',100),('pooled_bps','Mbps',1e-6),('worst_bps','Mbps',1e-6)]:
      for (m1,t1),(m2,t2) in [(('N','grid'),('N','margin')),(('R','grid'),('R','margin')),(('N','grid'),('R','grid')),(('N','margin'),('R','margin'))]:
       mu,hw=ci(vec(h,e,sh,b,m1,t1,metric,fac)-vec(h,e,sh,b,m2,t2,metric,fac));w.writerow([h,e,sh,b,metric,unit,m1+'-'+t1,m2+'-'+t2,*[f'{v:.9g}' for v in [mu,mu-hw,mu+hw]]]);
print('4800 extended records, all primary scores preserved; 1200 matched archived controls; 720 contrasts.')
