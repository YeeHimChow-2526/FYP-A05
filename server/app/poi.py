import json
import unicodedata
from pathlib import Path

from .models import Poi


class PoiCatalogUnavailable(RuntimeError):
    pass


def normalize_name(value: str) -> str:
    return "".join(
        character
        for character in unicodedata.normalize("NFKC", value).casefold()
        if character.isalnum()
    )


class PoiCatalog:
    def __init__(self, path: str) -> None:
        catalog_path = Path(path)
        if not catalog_path.is_absolute():
            catalog_path = Path(__file__).resolve().parents[1] / catalog_path
        with catalog_path.open(encoding="utf-8") as catalog_file:
            records = json.load(catalog_file)
        if not isinstance(records, list):
            raise ValueError("POI catalog must be a JSON array")
        self._pois = [Poi.model_validate(record) for record in records]
        self._aliases: dict[str, list[Poi]] = {}
        for poi in self._pois:
            for alias in [poi.name, *poi.aliases]:
                key = normalize_name(alias)
                self._aliases.setdefault(key, []).append(poi)

    def resolve(self, name: str) -> tuple[str, Poi | None, list[str]]:
        if not self._aliases:
            raise PoiCatalogUnavailable("POI catalog is empty")
        key = normalize_name(name)
        exact = self._aliases.get(key, [])
        if len(exact) == 1:
            return "SUCCESS", exact[0], []
        if len(exact) > 1:
            return "NEED_CLARIFICATION", None, [poi.name for poi in exact]

        suggestions = sorted(
            self._aliases,
            key=lambda alias: _edit_distance(key, alias),
        )[:3]
        names = []
        for alias in suggestions:
            for poi in self._aliases[alias]:
                if poi.name not in names:
                    names.append(poi.name)
        return "NOT_FOUND", None, names


def _edit_distance(left: str, right: str) -> int:
    previous = list(range(len(right) + 1))
    for row, left_char in enumerate(left, start=1):
        current = [row]
        for column, right_char in enumerate(right, start=1):
            current.append(
                min(
                    current[-1] + 1,
                    previous[column] + 1,
                    previous[column - 1] + (left_char != right_char),
                )
            )
        previous = current
    return previous[-1]
