import re

_PATTERN = re.compile(r"^([A-Za-z]+)[\s_\-]*0*(\d+)$")


def normalize_asset_id(raw) -> str | None:
    """Canonical PREFIX-0000. Mirrors AssetIdNormalizer.java exactly."""
    if raw is None:
        return None
    s = str(raw).strip()
    m = _PATTERN.match(s)
    if m:
        return f"{m.group(1).upper()}-{m.group(2).rjust(4, '0')}"
    flat = re.sub(r"[^A-Z0-9]", "", s.upper())
    return flat or None
