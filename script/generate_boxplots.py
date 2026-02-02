#!/usr/bin/env python3
"""
Generate boxplots for PRECISION, RECALL, AUC, KAPPA, MCC from a WEKA walk-forward CSV.
- Input:  weka_walkforward.csv (default: weka_walkforward.csv)
- Output: boxplot_precision.png, boxplot_recall.png, boxplot_auc.png, boxplot_kappa.png, boxplot_mcc.png
          and boxplots_metrics.pdf (5 pages)

Usage:
    python generate_boxplots.py --csv weka_walkforward.csv --out boxplots_out
"""
import argparse
from pathlib import Path

import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages

METRICS = ["PRECISION","RECALL","AUC","KAPPA","MCC"]

def norm_str(x):
    return "" if pd.isna(x) else str(x).strip()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", type=str, default="weka_walkforward.csv", help="Input CSV path")
    ap.add_argument("--out", type=str, default="boxplots_out", help="Output directory")
    args = ap.parse_args()

    csv_path = Path(args.csv)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(csv_path)

    # Force numeric metrics
    for c in METRICS:
        df[c] = pd.to_numeric(df[c], errors="coerce")

    # Flags
    df["FS"] = df["FEATURE_SELECTION"].map(lambda x: norm_str(x).lower() != "none")
    df["SMOTE"] = df["BALANCING"].map(lambda x: norm_str(x).upper() == "SMOTE")
    df["CS"] = df["COST_SENSITIVE"].map(lambda x: norm_str(x).lower() != "none")

    def make_group_key(row):
        return f'{row["MODEL"]} | FS={int(row["FS"])} | SMOTE={int(row["SMOTE"])} | CS={int(row["CS"])}'

    df["GROUP"] = df.apply(make_group_key, axis=1)

    group_order = (
        df[["MODEL","FS","SMOTE","CS","GROUP"]]
        .drop_duplicates()
        .sort_values(["MODEL","FS","SMOTE","CS"])
        ["GROUP"]
        .tolist()
    )

    def plot_metric(metric: str, save_png: Path):
        d = df[["GROUP", metric]].dropna()
        data, labels = [], []
        for g in group_order:
            vals = d.loc[d["GROUP"] == g, metric].values
            if vals.size == 0:
                continue
            data.append(vals)
            labels.append(g)

        plt.figure(figsize=(18, 7))
        plt.boxplot(data, labels=labels, showfliers=True)
        plt.xticks(rotation=60, ha="right")
        plt.ylabel(metric)
        plt.title(f"{metric} boxplot per configurazione (da {csv_path.name})")
        plt.tight_layout()
        plt.savefig(save_png, dpi=200)
        plt.close()

    pdf_path = out_dir / "boxplots_metrics.pdf"
    with PdfPages(pdf_path) as pdf:
        for metric in METRICS:
            png_path = out_dir / f"boxplot_{metric.lower()}.png"
            plot_metric(metric, png_path)

            # Add page to PDF (embed PNG)
            img = plt.imread(png_path)
            fig = plt.figure(figsize=(18, 7))
            plt.imshow(img)
            plt.axis("off")
            pdf.savefig(fig, bbox_inches="tight")
            plt.close(fig)

    print(f"Saved PNGs + PDF into: {out_dir}")

if __name__ == "__main__":
    main()
