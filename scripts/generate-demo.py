"""Generate original geometric covers + upload examples. Requires Pillow (dev only)."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import json, math, random

root = Path(__file__).resolve().parents[1]
out = root / 'frontend/public/demo'
out.mkdir(parents=True, exist_ok=True)
items = [
    ('lunar', '月下漫游', '灵感海报', 1, 'LUNAR', (113, 100, 230), '让平凡瞬间，拥有一场月光的想象。'),
    ('dune', '旷野来信', '旅行手记', 2, 'WANDER', (229, 158, 98), '将沿途的风景，装进自己的旅行手记。'),
    ('bloom', '花间序曲', '生活拼贴', 3, 'BLOOM', (217, 132, 160), '收集生活里，三个值得停留的片刻。'),
    ('tide', '蓝色潮汐', '旅行手记', 1, 'TIDAL', (70, 159, 188), '以一抹深蓝，记录与海相遇的时刻。'),
    ('orbit', '轨道之外', '灵感海报', 2, 'ORBIT', (171, 187, 109), '两张灵感，在此交汇成新的视角。'),
    ('still', '日常留白', '生活拼贴', 3, 'STILL', (187, 160, 130), '把日常的小小美好，留在这一帧。'),
]
fontpath = 'C:/Windows/Fonts/arial.ttf'
if not Path(fontpath).exists(): fontpath = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
def font(size): return ImageFont.truetype(fontpath, size)
templates = []
for index, (id, title, category, count, word, color, description) in enumerate(items):
    random.seed(index)
    w, h = 600, 850
    im = Image.new('RGB', (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            glow = max(0, 1 - math.hypot((x-300)/650, (y-300)/700))
            noise = random.randint(-4, 4)
            px[x,y] = tuple(max(0, min(255, int(c*(.18 + glow*.7) + noise))) for c in color)
    d = ImageDraw.Draw(im, 'RGBA')
    if id in ['lunar', 'orbit']:
        for r in range(190, 0, -1):
            a = r/190
            c = tuple(int(v*(1-a*.45)) for v in (231, 226, 215))
            d.ellipse((300-r, 350-r, 300+r, 350+r), fill=(*c,255))
        d.ellipse((180, 125, 545, 490), fill=(*[int(c*.24) for c in color], 245))
        for i in range(80):
            x,y = random.randint(25,575), random.randint(70,650)
            d.ellipse((x,y,x+2,y+2), fill=(255,255,255,150))
        for i in range(4):
            d.arc((50-i*25, 230-i*20, 550+i*25, 475+i*20), 0, 340, fill=(*color,180), width=2)
    elif id in ['dune', 'tide']:
        d.ellipse((420,140,500,220), fill=(255,237,198,240))
        for j in range(8):
            points = [(0,h),(0,450+j*40)]
            for x in range(0,w+10,10): points.append((x, 450+j*40+math.sin(x/160+j*.65)*70))
            points += [(w,h)]
            factor = .95-j*.085
            d.polygon(points, fill=(*[int(c*factor) for c in color],255))
    else:
        for j in range(9):
            a = j*math.pi*2/9
            x,y = 300+math.cos(a)*95,350+math.sin(a)*130
            d.ellipse((x-82,y-120,x+82,y+120), fill=(*color,140), outline=(255,240,220,110), width=1)
        d.ellipse((240,285,360,405), fill=(245,216,160,255))
        d.line((300,410,280,660), fill=(179,193,143,210), width=3)
    d.rectangle((25,25,575,825), outline=(255,255,255,75), width=1)
    d.text((49,50), 'M / STUDIO     —     STUDY 0'+str(index+1), font=font(16), fill=(255,255,255,190))
    d.text((46,665), word, font=font(67), fill=(255,249,235,255))
    d.text((50,751), 'EVERY MOMENT, REIMAGINED.', font=font(17), fill=(255,255,255,170))
    im.save(out / f'{id}.jpg', quality=92)
    templates.append(dict(id=id,title=title,category=category,coverUrl=f'/demo/{id}.jpg',description=description,requiredImageCount=count,acceptedMimeTypes=['image/png','image/jpeg'],maxFileSizeBytes=10485760))
    if index < 3:
        im.resize((420,595)).save(out / f'sample-{index+1}.png')
path = root / 'backend/src/main/resources/templates.json'
path.write_text(json.dumps(templates,ensure_ascii=False,indent=2),encoding='utf-8')
print('Generated six original covers, three PNG samples and backend template seed.')
