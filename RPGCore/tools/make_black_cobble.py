"""Draws the 16x16 black cobblestone texture (tileable) and a 4x4 preview. Usage: make_black_cobble.py [seed] [out.png]"""
import random, sys
from PIL import Image
random.seed(int(sys.argv[1]) if len(sys.argv) > 1 else 7)
N = 16
# (x, y, size) stones of different sizes, like real cobble
stones = [(3,3,1.25),(10,2,0.9),(14.5,6,0.85),(6.5,8,1.1),(1,9.5,0.8),(11.5,10.5,1.2),(4,13.5,0.95),(9,15,0.8),(14.5,14,0.75)]
stones = [(x+random.uniform(-.4,.4), y+random.uniform(-.4,.4), s) for x,y,s in stones]
def tvec(p, c):
    dx = p[0]-c[0]; dy = p[1]-c[1]
    dx -= N*round(dx/N); dy -= N*round(dy/N)
    return dx, dy
tone = [random.uniform(-1, 1) for _ in stones]
def color(v):  # v 0..1 -> near-black, faint violet
    return (int(20 + v*46), int(18 + v*40), int(24 + v*48), 255)
cell = [[0]*N for _ in range(N)]; mortar = [[False]*N for _ in range(N)]; rel = [[(0,0)]*N for _ in range(N)]
for y in range(N):
    for x in range(N):
        p = (x+.5, y+.5); ds = []
        for i,(cx,cy,s) in enumerate(stones):
            dx, dy = tvec(p,(cx,cy)); ds.append(((dx*dx + dy*dy*1.1)**.5 / s, i, dx, dy))
        ds.sort()
        cell[y][x] = ds[0][1]; rel[y][x] = (ds[0][2], ds[0][3])
        mortar[y][x] = ds[1][0] - ds[0][0] < 0.72
img = Image.new("RGBA",(N,N))
for y in range(N):
    for x in range(N):
        if mortar[y][x]:
            img.putpixel((x,y), (10,8,13,255) if random.random()<.75 else (16,13,20,255)); continue
        i = cell[y][x]; dx, dy = rel[y][x]
        v = 0.42 + tone[i]*0.10 + (-dx - dy)*0.03 + random.uniform(-.05,.05)
        up, left = mortar[(y-1)%N][x], mortar[y][(x-1)%N]
        down, right = mortar[(y+1)%N][x], mortar[y][(x+1)%N]
        if up or left: v += 0.20          # lit edge
        if down or right: v -= 0.16       # shadow edge
        if random.random() < 0.04: v -= 0.18  # pits
        img.putpixel((x,y), color(max(0,min(1,v))))
out = sys.argv[2] if len(sys.argv) > 2 else "black_cobblestone.png"
img.save(out)
prev = Image.new("RGBA",(N*4,N*4))
for i in range(4):
    for j in range(4): prev.paste(img,(i*N,j*N))
prev.resize((N*4*10,N*4*10),Image.NEAREST).save(out.replace(".png","_preview.png"))
