"""Build the offline village catalog and its thumbnail sheets from pinned public sources."""
import argparse
import hashlib
import io
import json
import re
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote
from urllib.request import urlopen

from PIL import Image

KING = ('ClashKingInc/ClashKingAssets', '9446c022333c591c4cab4422d745f4e90b1d3b00')
TRACKER = ('MasteR-NY/CoC-Base-Tracker', '95b96db4a34120ee99d1c815309cbf68aaf62ed4')
KINDS = ('buildings', 'traps', 'troops', 'spells', 'heroes', 'pets', 'equipment',
         'helpers', 'guardians', 'decorations', 'obstacles', 'skins', 'sceneries',
         'capital_house_parts')
WEB = Path(__file__).resolve().parents[1]
CELL, GRID = 96, 8


def load(url, cache):
    cached = cache / hashlib.sha256(url.encode()).hexdigest()
    if not cached.exists():
        failure = None
        for _ in range(3):
            try:
                with urlopen(url, timeout=30) as response:
                    cached.write_bytes(response.read())
                break
            except OSError as error:
                failure = error
        else:
            raise failure
    return cached.read_bytes()


def raw(source, path, cache):
    repo, revision = source
    return load(f'https://raw.githubusercontent.com/{repo}/{revision}/{quote(path)}', cache)


def tree(source, cache):
    repo, revision = source
    result = json.loads(load(f'https://api.github.com/repos/{repo}/git/trees/{revision}?recursive=1', cache))
    if result.get('truncated'):
        raise ValueError(f'Incomplete Git tree for {repo}')
    return {entry['path']: entry['sha'] for entry in result['tree'] if entry['type'] == 'blob'}


def name_path(name):
    return name.lower().replace(' ', '_').replace('.', '').replace('?', '').replace('’', '').replace('\\q', '')


def king_art(kind, item, assets):
    slug = name_path(item['name'])
    folder = 'builder-base' if item.get('village') == 'builderBase' else 'home-village'
    if kind in ('buildings', 'traps'):
        pattern = re.compile(fr'assets/{kind}/{folder}/{re.escape(slug)}/level_(\d+)\.webp')
        levels = {int(match[1]): path for path in assets if (match := pattern.fullmatch(path))}
        return None, levels
    paths = {
        'decorations': [f'{kind}/{folder}/{slug}.webp'],
        'obstacles': [f'{kind}/{folder}/{slug}.webp'],
        'sceneries': [f'{kind}/{slug}/icon.webp', item.get('thumbnail') or ''],
        'capital_house_parts': [f'{kind}/{item["_id"]}.webp'],
        'helpers': [f'{kind}/{slug}.webp', f'{kind}/{slug}/icon.webp'],
    }.get(kind, [f'{kind}/{slug}/icon.webp', f'{kind}/{slug}.webp'])
    return next((f'assets/{path}' for path in paths if f'assets/{path}' in assets), None), {}


def thumbnail(source_path, cache):
    source, path = source_path
    with Image.open(io.BytesIO(raw(source, path, cache))) as original:
        art = original.convert('RGBA')
    art.thumbnail((CELL - 8, CELL - 8), Image.Resampling.LANCZOS)
    return art


def build(cache):
    with ThreadPoolExecutor(max_workers=20) as workers:
        king_tree = workers.submit(tree, KING, cache)
        tracker_tree = workers.submit(tree, TRACKER, cache)
        records = {kind: workers.submit(raw, KING, f'assets/static_data/{kind}.json', cache) for kind in KINDS}
        localized = workers.submit(raw, KING, 'assets/translations/CN.json', cache)
        tracker_files = {name: workers.submit(raw, TRACKER, f'app/src/main/assets/{name}.json', cache)
                         for name in ('item-identities', 'level-art')}
        king, tracker = king_tree.result(), tracker_tree.result()
        chinese = json.loads(localized.result())
        identities = json.loads(tracker_files['item-identities'].result())['items']
        tracker_levels = json.loads(tracker_files['level-art'].result())['items']
        sprites, by_blob, items = [], {}, {}

        def sprite(source, path):
            assets = king if source == KING else tracker
            if not path or path not in assets:
                return None
            blob = assets[path]
            if blob not in by_blob:
                by_blob[blob] = len(sprites)
                sprites.append((source, path))
            return by_blob[blob]

        def tracker_sprite(stem):
            if stem:
                return sprite(TRACKER, f'app/src/main/res/drawable-nodpi/{stem}.webp')
            return None

        for kind, fetch in records.items():
            for item in json.loads(fetch.result())['items']:
                item_id = str(item['_id'])
                entry = {'name': item['name']}
                translated = chinese.get(item.get('TID', {}).get('name'))
                if translated and not translated.startswith('TID_'):
                    entry['zh'] = translated
                icon, levels = king_art(kind, item, king)
                tiles = {str(level): sprite(KING, path) for level, path in sorted(levels.items())}
                for level, stem in tracker_levels.get(item_id, {}).items():
                    if level.isdigit() and level not in tiles and (tile := tracker_sprite(stem)) is not None:
                        tiles[level] = tile
                icon_tile = sprite(KING, icon)
                if icon_tile is None:
                    icon_tile = tracker_sprite(identities.get(item_id, {}).get('icon'))
                if icon_tile is not None:
                    entry['icon'] = icon_tile
                if tiles:
                    entry['levels'] = tiles
                items[item_id] = entry

        for item_id, item in identities.items():
            if item_id in items:
                continue
            tile = tracker_sprite(item.get('icon'))
            items[item_id] = {'name': item['name']}
            if tile is not None:
                items[item_id]['icon'] = tile

        directory = WEB / 'public/coc-village' / KING[1][:8]
        directory.mkdir(parents=True, exist_ok=True)
        for start in range(0, len(sprites), GRID * GRID):
            sheet = Image.new('RGBA', (CELL * GRID, CELL * GRID))
            for cell, art in enumerate(workers.map(lambda source: thumbnail(source, cache),
                                                    sprites[start:start + GRID * GRID])):
                x = (cell % GRID) * CELL + (CELL - art.width) // 2
                y = (cell // GRID) * CELL + (CELL - art.height) // 2
                sheet.paste(art, (x, y))
            sheet.save(directory / f'{start // (GRID * GRID)}.webp', 'WEBP', quality=82, method=6)
        entries = ',\n'.join(
            f'    "{item_id}": {json.dumps(item, ensure_ascii=False, separators=(",", ":"))}'
            for item_id, item in sorted(items.items(), key=lambda entry: int(entry[0]))
        )
        (WEB / 'src/coc-catalog.json').write_text(
            f'{{\n  "edition": "{KING[1][:8]}",\n  "grid": {GRID},\n  "items": {{\n{entries}\n  }}\n}}\n',
            encoding='utf-8')
        print(f'{len(items)} IDs, {len(sprites)} unique thumbnails')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    build(args.cache)
