from pathlib import Path
import cv2,json
from features import lighting
root=Path(__file__).resolve().parent
out=root/'lighting_fixtures';out.mkdir(exist_ok=True)
for scene in json.loads((root/'data/annotations.json').read_text()):
    name=scene['group'];gray=cv2.imread(str(root/'data'/(name+'.png')),0)
    for i in range(6):cv2.imwrite(str(out/(name+f'_light{i}.png')),lighting(gray,i))
print(out)
