from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image
from PIL import ImageOps

ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = ROOT.parent
SOURCE_DIR = PROJECT_ROOT / "Pictures"
ASSET_DIR = ROOT / "demo_assets" / "community_seed"
MANIFEST_PATH = ASSET_DIR / "manifest.json"
DEMO_DB_PATH = ROOT / "demo_app_data.db"
MAX_SIDE = 1600
JPEG_QUALITY = 82

SOURCE_SPECS = [
    {
        "source_name": "IMG_0058.jpeg",
        "captured_at": "2026-03-29T10:19:04Z",
        "primary_category": "food",
        "subject": "spicy_fish",
        "scene_type": "food",
        "place_tag": "食堂夜宵档",
        "caption": "这一锅刚上桌，今天的快乐值就已经够了。",
        "review_text": "正上方构图把汤色和配菜都压住了，暖光下很有烟火气。",
        "rating": 5,
        "tags": ["food", "dinner", "shared_meal", "warm_light"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_0806.jpeg",
        "captured_at": "2024-05-24T14:19:47Z",
        "primary_category": "flower",
        "subject": "gardenia_night",
        "scene_type": "general",
        "place_tag": "楼下花坛",
        "caption": "晚风里闻到一点栀子香，突然就不着急回去。",
        "review_text": "近距离拍花瓣层次很稳，暗背景反而把白花衬得更轻。",
        "rating": 4,
        "tags": ["flower", "night", "gardenia", "quiet"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_1200.jpeg",
        "captured_at": "2024-07-19T09:44:16Z",
        "primary_category": "landscape",
        "subject": "lotus_pavilion",
        "scene_type": "landscape",
        "place_tag": "曲院风荷",
        "caption": "荷叶铺开以后，夏天就真的落地了。",
        "review_text": "柳条刚好做前景取景框，亭子压在中段位置特别耐看。",
        "rating": 5,
        "tags": ["landscape", "lotus", "summer", "west_lake"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_1203.jpeg",
        "captured_at": "2024-07-19T09:57:20Z",
        "primary_category": "landscape",
        "subject": "west_lake_ducks",
        "scene_type": "landscape",
        "place_tag": "西湖边",
        "caption": "水面起一点风，连鸭子经过都像排过队。",
        "review_text": "天空和湖面都留得够开，广角风景最怕挤，这张很舒展。",
        "rating": 5,
        "tags": ["landscape", "lake", "ducks", "summer"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_1222.jpeg",
        "captured_at": "2024-07-19T10:59:16Z",
        "primary_category": "landscape",
        "subject": "pagoda_lake_frame",
        "scene_type": "landscape",
        "place_tag": "湖畔林荫",
        "caption": "躲进树影里看湖面，晚霞像被悄悄框住了。",
        "review_text": "天然枝叶当边框很有层次，远处塔身正好把视线收住。",
        "rating": 4,
        "tags": ["landscape", "sunset", "lake", "pagoda"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_4516.jpeg",
        "captured_at": "2025-04-05T10:50:11Z",
        "primary_category": "portrait",
        "subject": "blue_hour_beach_pose",
        "scene_type": "portrait",
        "place_tag": "海边栈桥",
        "caption": "海风很大，但张开手臂的时候心情也会跟着松开。",
        "review_text": "蓝调时刻拍人像很稳，背景船灯虚开以后氛围就全有了。",
        "rating": 5,
        "tags": ["portrait", "beach", "blue_hour", "travel"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_4539.jpeg",
        "captured_at": "2025-04-05T21:30:49Z",
        "primary_category": "night",
        "subject": "windfarm_sunset",
        "scene_type": "night",
        "place_tag": "风车海岸",
        "caption": "太阳刚压下去的那几分钟，海边像被重新点亮了一次。",
        "review_text": "地平线压低以后颜色更纯净，等海鸟飞进来就很有故事感。",
        "rating": 5,
        "tags": ["night", "sunset", "sea", "windfarm"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_4991.jpeg",
        "captured_at": "2025-05-01T06:39:42Z",
        "primary_category": "indoor",
        "subject": "poetry_flower_wall",
        "scene_type": "general",
        "place_tag": "书店花墙",
        "caption": "被句子和花一起围住的角落，适合慢慢看两遍。",
        "review_text": "室内暖光很柔，贴墙斜拍能把文字层次和花色都保住。",
        "rating": 4,
        "tags": ["indoor", "bookstore", "flowers", "lifestyle"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_4997.jpeg",
        "captured_at": "2025-05-01T07:39:50Z",
        "primary_category": "travel",
        "subject": "seaside_wish_lock",
        "scene_type": "landscape",
        "place_tag": "海边堤岸",
        "caption": "海风吹得很轻，这种小小的纪念物反而最容易记住当天。",
        "review_text": "前景有小物件的时候，旅行照片会比纯风景更有记忆点。",
        "rating": 4,
        "tags": ["travel", "sea", "detail", "memory"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_6114.jpeg",
        "captured_at": "2025-07-07T10:53:29Z",
        "primary_category": "landscape",
        "subject": "evening_clouds",
        "scene_type": "landscape",
        "place_tag": "校园天边",
        "caption": "抬头那一下，今天的天已经把情绪写完了。",
        "review_text": "低机位把树线压成剪影之后，云层的纹理一下子就出来了。",
        "rating": 4,
        "tags": ["landscape", "sky", "clouds", "campus"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_6122.jpeg",
        "captured_at": "2025-07-08T11:17:30Z",
        "primary_category": "landscape",
        "subject": "crimson_sky",
        "scene_type": "landscape",
        "place_tag": "海边松林",
        "caption": "天边烧起来的时候，连站着发呆都显得有意义。",
        "review_text": "整片云层都在发光，留一条黑色树线会让颜色更稳。",
        "rating": 5,
        "tags": ["landscape", "sunset", "clouds", "seaside"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_6238.jpeg",
        "captured_at": "2025-07-14T08:35:43Z",
        "primary_category": "travel",
        "subject": "coastline_islet",
        "scene_type": "landscape",
        "place_tag": "山海步道",
        "caption": "站到拐角那一刻，海面和小岛一起把呼吸放慢了。",
        "review_text": "前景松枝压一下，海面会更有纵深，旅行感也更完整。",
        "rating": 4,
        "tags": ["travel", "coast", "sea", "walk"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_6254.jpeg",
        "captured_at": "2025-07-16T03:12:13Z",
        "primary_category": "pet",
        "subject": "street_cat",
        "scene_type": "general",
        "place_tag": "宿舍楼下",
        "caption": "路过的时候对视了一秒，今天就被这只小猫签收了。",
        "review_text": "宠物图只要眼神抓住了，背景再简单也会很有记忆点。",
        "rating": 5,
        "tags": ["pet", "cat", "campus", "daily"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_7012.jpeg",
        "captured_at": "2025-10-06T06:41:36Z",
        "primary_category": "pet",
        "subject": "corgi_pat",
        "scene_type": "general",
        "place_tag": "街角小院",
        "caption": "伸手的瞬间它就坐好了，营业能力满分。",
        "review_text": "俯拍加互动动作很适合宠物，画面会显得特别亲近。",
        "rating": 5,
        "tags": ["pet", "dog", "interaction", "daily"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_7459.jpeg",
        "captured_at": "2025-11-14T11:41:36Z",
        "primary_category": "food",
        "subject": "sweet_soup_bowl",
        "scene_type": "food",
        "place_tag": "糖水铺",
        "caption": "桂花、红豆和奶香碰在一起的时候，冬天就变温柔了。",
        "review_text": "手部动作带一点互动感，甜品照会比静物更有生活气。",
        "rating": 4,
        "tags": ["food", "dessert", "sweet_soup", "warm"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_8573.jpeg",
        "captured_at": "2025-12-23T10:12:34Z",
        "primary_category": "food",
        "subject": "lychee_birthday_cake",
        "scene_type": "food",
        "place_tag": "生日聚餐",
        "caption": "二十岁要记住的，不止蜡烛，还有这一桌人的笑声。",
        "review_text": "蛋糕做主角，背景食物轻轻虚开，庆祝感就会更集中。",
        "rating": 5,
        "tags": ["food", "cake", "birthday", "friends"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_9007.jpeg",
        "captured_at": "2026-01-11T07:52:36Z",
        "primary_category": "landscape",
        "subject": "snowy_beach_pines",
        "scene_type": "landscape",
        "place_tag": "冬日海岸",
        "caption": "雪落在海边松树上，冬天忽然就有了安静的轮廓。",
        "review_text": "前景树干把海面分出了层次，冬天的风景反而更适合慢慢看。",
        "rating": 5,
        "tags": ["landscape", "winter", "snow", "sea"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_9008.jpeg",
        "captured_at": "2026-01-11T08:41:29Z",
        "primary_category": "landscape",
        "subject": "winter_beach_sunset",
        "scene_type": "landscape",
        "place_tag": "雪后海边",
        "caption": "海风很冷，但粉色晚霞一出来就又舍不得走了。",
        "review_text": "海岸线放低以后云层会更轻，冬天也能拍得很柔和。",
        "rating": 5,
        "tags": ["landscape", "winter", "sunset", "sea"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_9009.jpeg",
        "captured_at": "2026-01-11T08:44:40Z",
        "primary_category": "landscape",
        "subject": "pastel_cloud_sea",
        "scene_type": "landscape",
        "place_tag": "海边观景台",
        "caption": "今天的云像被轻轻烤过一遍，站着看就已经很满足。",
        "review_text": "这种满天云层很吃节奏，早点按下去就容易失掉最软的颜色。",
        "rating": 4,
        "tags": ["landscape", "clouds", "sea", "sunset"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_9981.jpeg",
        "captured_at": "2026-03-25T08:15:59Z",
        "primary_category": "flower",
        "subject": "blossom_moon",
        "scene_type": "general",
        "place_tag": "校园樱花道",
        "caption": "抬头的时候刚好看见月亮，春天也就有了证据。",
        "review_text": "把花枝和天空都留开，季节感会比特写更轻更长久。",
        "rating": 5,
        "tags": ["flower", "spring", "blossom", "campus"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_9983.jpeg",
        "captured_at": "2026-03-25T08:17:23Z",
        "primary_category": "flower",
        "subject": "blossom_facade",
        "scene_type": "general",
        "place_tag": "教学楼下",
        "caption": "花枝把整栋楼都柔下来，连下课的脚步都像变慢了。",
        "review_text": "把建筑边缘当背景支撑，花会显得更立体，也更像春天的日常。",
        "rating": 4,
        "tags": ["flower", "spring", "building", "campus"],
        "allow_as_post": True,
    },
    {
        "source_name": "IMG_9985.jpeg",
        "captured_at": "2026-03-25T08:23:58Z",
        "primary_category": "flower",
        "subject": "blossom_branch",
        "scene_type": "general",
        "place_tag": "春日松林",
        "caption": "有些花不用靠近，远远看着就已经够轻了。",
        "review_text": "枝条斜下来以后画面会自然流动，春天的松弛感一下就出来了。",
        "rating": 4,
        "tags": ["flower", "spring", "branch", "outdoor"],
        "allow_as_post": True,
    },
]


def build_asset_name(spec: dict[str, object]) -> str:
    date_text = str(spec["captured_at"])[:10].replace("-", "")
    return f"{date_text}_{spec['primary_category']}_{spec['subject']}_01.jpg"


def build_manifest_entries() -> list[dict[str, object]]:
    entries: list[dict[str, object]] = []
    for spec in SOURCE_SPECS:
        item = dict(spec)
        item["asset_name"] = build_asset_name(spec)
        entries.append(item)
    entries.sort(key=lambda entry: (str(entry["captured_at"]), str(entry["asset_name"])))
    return entries


def build_demo_assets(entries: list[dict[str, object]]) -> None:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    for path in ASSET_DIR.glob("*.jpg"):
        path.unlink()

    for entry in entries:
        source_path = SOURCE_DIR / str(entry["source_name"])
        if not source_path.exists():
            raise FileNotFoundError(f"missing source image: {source_path}")
        target_path = ASSET_DIR / str(entry["asset_name"])
        with Image.open(source_path) as image:
            normalized = ImageOps.exif_transpose(image).convert("RGB")
            if max(normalized.size) > MAX_SIDE:
                normalized.thumbnail((MAX_SIDE, MAX_SIDE), Image.Resampling.LANCZOS)
            normalized.save(target_path, format="JPEG", quality=JPEG_QUALITY, optimize=True)


def write_manifest(entries: list[dict[str, object]]) -> None:
    payload = {
        "version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "items": entries,
    }
    MANIFEST_PATH.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def rebuild_demo_database(entry_count: int) -> None:
    for suffix in ("", "-journal", "-wal", "-shm"):
        target = Path(f"{DEMO_DB_PATH}{suffix}")
        if target.exists():
            target.unlink()

    os.environ["APP_DB_PATH"] = str(DEMO_DB_PATH)
    sys.path.insert(0, str(ROOT))

    from app.core.database import ensure_db
    from app.core.database import open_db
    from app.services.community_seed import seed_demo_content

    ensure_db()
    conn = open_db()
    try:
        inserted = seed_demo_content(conn, reset=True, max_posts=entry_count)
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()

    if inserted <= 0:
        raise RuntimeError("failed to seed demo database")


def main() -> None:
    entries = build_manifest_entries()
    build_demo_assets(entries)
    write_manifest(entries)
    rebuild_demo_database(len(entries))
    print(f"generated {len(entries)} demo assets")
    print(f"manifest: {MANIFEST_PATH}")
    print(f"demo db:  {DEMO_DB_PATH}")


if __name__ == "__main__":
    main()
