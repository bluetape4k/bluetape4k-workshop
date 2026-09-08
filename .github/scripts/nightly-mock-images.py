"""소비자 BOM에서 Nightly mock 이미지 태그를 결정하고 로컬 이미지를 검증한다."""

import argparse
import re
import subprocess
import tomllib
from pathlib import Path


STABLE_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
SNAPSHOT_VERSION = re.compile(r"([0-9]+\.[0-9]+\.[0-9]+)-SNAPSHOT")


def image_names(version: str) -> list[str]:
    if not STABLE_VERSION.fullmatch(version):
        raise ValueError("Nightly requires an official stable dependencies version")
    return [
        f"bluetape4k/{name}:{version}"
        for name in ("mock-web-server", "mock-webflux-server")
    ]


def resolve_consumer_version(version: str, *, allow_snapshot: bool = False) -> tuple[str, str]:
    if STABLE_VERSION.fullmatch(version):
        return version, version
    snapshot = SNAPSHOT_VERSION.fullmatch(version)
    if allow_snapshot and snapshot:
        return snapshot.group(1), "develop"
    raise ValueError("Nightly requires an official stable dependencies version")


def read_catalog_version(catalog: Path) -> str:
    with catalog.open("rb") as source:
        return tomllib.load(source)["versions"]["bluetape4k-dependencies-version"]


def read_version(catalog: Path, *, allow_snapshot: bool = False) -> str:
    image_version, _ = resolve_consumer_version(
        read_catalog_version(catalog), allow_snapshot=allow_snapshot
    )
    image_names(image_version)
    return image_version


def read_source_ref(catalog: Path, *, allow_snapshot: bool = False) -> str:
    _, source_ref = resolve_consumer_version(
        read_catalog_version(catalog), allow_snapshot=allow_snapshot
    )
    return source_ref


def validate_source_ref(source_ref: str, trusted_source_ref: str) -> str:
    if source_ref != trusted_source_ref:
        raise ValueError(
            f"Resolved source ref {source_ref!r} does not match "
            f"trusted workflow ref {trusted_source_ref!r}"
        )
    return source_ref


def inspect_images(version: str) -> None:
    subprocess.run(["docker", "image", "inspect", *image_names(version)], check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog", type=Path, default=Path("gradle/libs.versions.toml")
    )
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--expected-source-ref")
    parser.add_argument("--inspect", action="store_true")
    parser.add_argument(
        "--allow-snapshot",
        action="store_true",
        help="Use the snapshot base version and bluetape4k-projects develop source",
    )
    args = parser.parse_args()
    version = read_version(args.catalog, allow_snapshot=args.allow_snapshot)
    source_ref = read_source_ref(args.catalog, allow_snapshot=args.allow_snapshot)
    if args.expected_source_ref:
        validate_source_ref(source_ref, args.expected_source_ref)
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"version={version}\n")
    if args.inspect:
        inspect_images(version)
    print(f"Nightly mock images: {', '.join(image_names(version))}")


if __name__ == "__main__":
    main()
