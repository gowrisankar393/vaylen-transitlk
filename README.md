# TransitLK — Driver Monitoring System

> **Project:** TransitLK  
> **Author:** Isira Sanjan  
> **Module:** Driver Monitoring System (DMS)  
> **Environment:** Google Colab  

---

## Overview

The **Driver Monitoring System** is a two-model computer vision module for the TransitLK project, designed to enhance bus passenger safety by continuously analysing the driver's behaviour in real time.

It consists of two independent models:

| Model | Task | Algorithm |
|-------|------|-----------|
| **Driver Drowsiness Detection** | Detects whether the driver's eyes are open or closed to flag drowsy driving | Custom CNN (TensorFlow/Keras) |
| **Foul Activity Detection** | Classifies the driver's current activity into one of five safety categories | MobileNetV2 (Transfer Learning) |

---

## Repository Structure

```
drowsiness_training.ipynb         # Eye state classification model (CNN)
Foul_Detection_tested00.ipynb     # Driver activity classification model (MobileNetV2)
```

---

## Model 1 — Driver Drowsiness Detection

**Notebook:** `drowsiness_training.ipynb`  
**Task:** Binary classification — `Closed (Drowsy)` vs `Open (Active)`  
**Framework:** TensorFlow / Keras

### Dataset
- **Source:** MRL Eye Dataset (`mrlEyes_2018_01`) — uploaded from Google Drive (`archive.zip`)
- **Total images:** 84,898 grayscale eye images
- **Class distribution:** Open: 42,952 · Closed: 41,946 (well balanced)
- **Split:** 80% train (67,918) / 20% validation (16,980), stratified

### Preprocessing
- Images resized to **80×80**, loaded in **grayscale**
- Pixel values rescaled to `[0, 1]`
- Labels parsed from filename: the 5th `_`-separated token in each filename encodes the eye state (`0` = closed, `1` = open)

### Model Architecture

```
Input (80×80×1)
  └── Conv2D(32, 3×3, relu)
      └── MaxPooling2D(2×2)
          └── Conv2D(64, 3×3, relu)
              └── MaxPooling2D(2×2)
                  └── Conv2D(128, 3×3, relu)
                      └── MaxPooling2D(2×2)
                          └── Flatten
                              └── Dense(128, relu)
                                  └── Dropout(0.5)
                                      └── Dense(1, sigmoid)  ← binary output
```

**Optimizer:** Adam · **Loss:** Binary Crossentropy · **Batch size:** 32 · **Epochs:** 10

### Training Results

| Epoch | Train Accuracy | Val Accuracy | Val Loss |
|-------|---------------|-------------|----------|
| 1 | 88.81% | 97.20% | 0.0781 |
| 5 | 98.45% | 98.69% | 0.0391 |
| 10 | **98.89%** | **98.57%** | 0.0448 |

### Evaluation Results

| Metric | Closed (Drowsy) | Open (Active) | Overall |
|--------|----------------|--------------|---------|
| Precision | 0.99 | 0.98 | — |
| Recall | 0.98 | 0.99 | — |
| F1-Score | 0.99 | 0.99 | — |
| **Accuracy** | — | — | **98.57%** |
| **ROC-AUC** | — | — | **0.9990** |

### Output Files

| File | Description |
|------|-------------|
| `drowsiness_eye_model.h5` | Trained Keras model saved locally and to Google Drive |

---

## Model 2 — Foul Activity Detection

**Notebook:** `Foul_Detection_tested00.ipynb`  
**Task:** 5-class classification of driver activity  
**Framework:** TensorFlow / Keras · **Base Model:** MobileNetV2 (ImageNet weights)

### Classes

| Index | Class | Description |
|-------|-------|-------------|
| 0 | `Other Activities` | Miscellaneous non-driving behaviour |
| 1 | `Safe Driving` | Driver attentive and focused |
| 2 | `Talking Phone` | Driver talking on a phone |
| 3 | `Texting Phone` | Driver texting on a phone |
| 4 | `Turning` | Driver turning/looking away |

### Dataset
- **Source:** Kaggle — [`robinreni/revitsone-5class`](https://www.kaggle.com/datasets/robinreni/revitsone-5class) (Revitsone 5-class Driver Dataset)
- **Total images:** 10,751 (after removing 15 corrupted files)
- **Split:** 80% train (8,601) / 20% validation (2,150), `seed=123`
- **Image size:** 224×224 RGB · **Batch size:** 32

**Per-class validation counts:**

| Class | Support |
|-------|---------|
| Other Activities | 421 |
| Safe Driving | 454 |
| Talking Phone | 419 |
| Texting Phone | 448 |
| Turning | 408 |

**Class weights** were computed using `sklearn.utils.class_weight.compute_class_weight` to handle mild imbalances:

| Class | Weight |
|-------|--------|
| Other Activities | 1.013 |
| Safe Driving | 0.984 |
| Talking Phone | 0.983 |
| Texting Phone | 0.980 |
| Turning | 1.043 |

### Model Architecture

```
Input (224×224×3)
  └── Lambda (MobileNetV2 preprocess_input)
      └── MobileNetV2 (ImageNet weights, frozen)
          └── GlobalAveragePooling2D
              └── Dropout(0.2)
                  └── Dense(5, softmax)  ← 5-class output
```

**Optimizer:** Adam · **Loss:** Categorical Crossentropy · **Batch size:** 32  
**Max Epochs:** 20 (with early stopping, patience=3 on `val_loss`)  
**Best weights** saved via `ModelCheckpoint` monitoring `val_accuracy`

### Training Results (Selected Epochs)

| Epoch | Train Accuracy | Val Accuracy |
|-------|---------------|-------------|
| 1 | 44.68% | 82.98% |
| 4 | 86.23% | 91.95% |
| 9 | 90.04% | 94.09% |
| 12 | 90.95% | 94.56% |
| **19** | **91.81%** | **95.26%** ← best |
| 20 | 91.83% | 93.58% |

Best weights restored from **epoch 19**.

### Evaluation Results

| Class | Precision | Recall | F1-Score | Support |
|-------|-----------|--------|----------|---------|
| Other Activities | 0.92 | 0.90 | 0.91 | 421 |
| Safe Driving | 0.96 | 0.96 | 0.96 | 454 |
| Talking Phone | 0.98 | 0.94 | 0.96 | 419 |
| Texting Phone | 0.94 | 0.98 | 0.96 | 448 |
| Turning | 0.97 | 0.99 | 0.98 | 408 |
| **Overall** | **0.95** | **0.95** | **0.95** | **2150** |

### Output Files

| File | Description |
|------|-------------|
| `best_driver_model_tested.h5` | Best checkpoint saved during training (HDF5) |
| `best_driver_model.keras` | Final model in native Keras format |
| `driver_model_weights.weights.h5` | Saved model weights only |

> All outputs are saved directly to `MyDrive/` in Google Drive.

---

## Requirements

```txt
tensorflow>=2.19
opencv-python
scikit-learn
pandas
numpy
matplotlib
seaborn
gdown
Pillow
kaggle
```

Install:
```bash
pip install tensorflow opencv-python scikit-learn pandas numpy matplotlib seaborn gdown Pillow kaggle
```

---

## About TransitLK

**TransitLK** is a project to modernise public bus transit in Sri Lanka. The Driver Monitoring System is the safety awareness layer, designed to detect drowsy or distracted driving in real time and trigger appropriate alerts to improve road safety for passengers and the public.

---

## Author

**Isira Sanjan**  
TransitLK Project — Driver Monitoring System

---

## License

All rights reserved by the author unless otherwise stated.
