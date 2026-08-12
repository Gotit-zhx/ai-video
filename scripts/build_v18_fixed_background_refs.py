from pathlib import Path

from PIL import Image
from rembg import new_session, remove


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "minimax_h3_v16_closer_continuous_output_11"
OUTPUT = ROOT / "minimax_h3_v18_fixed_background_refs"
BACKGROUND = Path(
    r"C:\Users\yingdao\.codex\generated_images\019fe99c-58fe-78d3-b0bf-a8bc1e3369d8"
    r"\exec-26e08aaf-73e7-49ec-bc05-b7de1d1c8b3d.png"
)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    background = Image.open(BACKGROUND).convert("RGBA")
    target_size = background.size
    session = new_session("birefnet-portrait")

    for index in range(1, 10):
        composite_path = OUTPUT / f"{index:02d}.png"
        foreground_path = OUTPUT / f"{index:02d}-foreground.png"
        if composite_path.exists() and foreground_path.exists():
            continue
        source_path = SOURCE / f"{index:02d}.png"
        source = Image.open(source_path).convert("RGBA")
        if source.size != target_size:
            source = source.resize(target_size, Image.Resampling.LANCZOS)

        foreground = remove(
            source,
            session=session,
            alpha_matting=True,
            alpha_matting_foreground_threshold=245,
            alpha_matting_background_threshold=10,
            alpha_matting_erode_size=4,
        )
        foreground.save(foreground_path)
        composite = Image.alpha_composite(background.copy(), foreground)
        composite.convert("RGB").save(composite_path, quality=98)

    background.convert("RGB").save(OUTPUT / "background-plate.png", quality=98)


if __name__ == "__main__":
    main()
