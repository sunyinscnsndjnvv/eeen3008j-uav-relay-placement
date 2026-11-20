from statistics import mean, stdev
from math import sqrt

# (x, y, access score, allocated backhaul)
# All rates use one common unit, e.g. Mbps.
def choose(rows, margin):
    rows = sorted(rows)  # grid order: x, y
    def rank(row):
        x, y, a, b = row
        return (min(a, b), a if margin else 0)
    return max(rows, key=rank)[:2]

# Nominal access for three sites in Fig. 2.
sites = [(250, 200, .5054517, .5),
         (300, 275, .7686833, .5),
         (375, 325, 1.0519986, .5)]
assert choose(sites, False) == (250, 200)
assert choose(sites, True) == (375, 325)

# Each count is out of 10,000 held-out rates.
grid = [1049, 1537, 981, 844, 1059,
        1007, 1007, 1258, 1226, 981,
        1034, 1576, 898, 879, 937,
        1313, 1265, 814, 1279, 947]
margin = [0, 0, 0, 127, 0,
        0, 465, 1019, 154, 696,
        114, 239, 0, 0, 0,
        0, 157, 0, 83, 0]
d = [(g-m)/100 for g, m in zip(grid, margin)]
avg = mean(d)
half = 2.093024 * stdev(d) / sqrt(len(d))
print(round(avg, 4), round(avg-half, 4),
      round(avg+half, 4))
