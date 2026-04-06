# TransitLK — Multi-Sensor Fusion Crash Detection (MSFCD)

> **Project:** TransitLK  
> **Author:** Gowrisankar Sivakumar  
> **Module:** Multi-Sensor Fusion Crash Detection (MSFCD)  
> **Environment:** Google Colab  

---

## Overview

The **MSFCD** module is a two-model crash detection system for the TransitLK project. It fuses outputs from two independent models — a **Sensor Data Processing model** and a **Computer Vision Processing model** — to classify a bus event as either a **crash** or **normal**.

By combining physical sensor signals (accelerometer, gyroscope, GPS) with visual evidence (dashcam frames), the system achieves more robust and reliable crash detection than either modality alone.

---

## System Architecture

```
┌─────────────────────────────────┐     ┌──────────────────────────────────┐
│   Sensor Data Processing (SDP)  │     │  Computer Vision Processing (CVP) │
│   XGBoost Classifier            │     │  MobileNetV2 + TensorFlow         │
│                                 │     │                                   │
│  Inputs: accel_x/y/z,           │     │  Input: Dashcam image (224×224)   │
│          gyro_x/y/z,            │     │                                   │
│          gps_lat/lon/speed      │     │  Output: crash probability        │
│  + derived: accel_magnitude,    │     │                                   │
│             gyro_magnitude,     │     │                                   │
│             gps_speed_abs       │     │                                   │
│                                 │     │                                   │
│  Output: crash probability      │     │                                   │
└────────────────┬────────────────┘     └─────────────────┬────────────────┘
                 │                                        │
                 └──────────────────┬─────────────────────┘
                                    ▼
                         ┌─────────────────────┐
                         │   Fusion Layer       │
                         │  Final Classification│
                         │  Crash / Normal      │
                         └─────────────────────┘
```

---

## Repository Structure

```
TransitLK_MSFCD_SDP_XGB.ipynb       # Sensor Data Processing model (XGBoost)
TransitLK_MSFCD_CVP_TF.ipynb        # Computer Vision Processing model (TensorFlow / MobileNetV2)
```

---

## Model 1 — Sensor Data Processing (SDP)

**Notebook:** `TransitLK_MSFCD_SDP_XGB.ipynb`  
**Algorithm:** XGBoost Classifier

### Dataset
- **File:** `nthsc_telemetry_records.csv`
- **Samples:** 6,000 telemetry records (15 columns)
- **Crash rate:** 2.67% (160 crashes / 5,840 normal)
- **Class balancing:** SMOTE (Synthetic Minority Over-sampling Technique, `k_neighbors=3`) → balanced to 5,840 each class

### Input Sensors (Mobile Features)
Only features measurable from an Android phone are used:

| Sensor | Columns |
|--------|---------|
| Accelerometer | `accel_x`, `accel_y`, `accel_z` |
| Gyroscope | `gyro_x`, `gyro_y`, `gyro_z` |
| GPS | `gps_lat`, `gps_lon`, `gps_speed` |

### Feature Engineering
Three derived features are computed on top of the raw sensor readings:

| Feature | Formula | Purpose |
|---------|---------|---------|
| `accel_magnitude` | √(accel_x² + accel_y² + accel_z²) | Total G-force |
| `gyro_magnitude` | √(gyro_x² + gyro_y² + gyro_z²) | Total rotation force |
| `gps_speed_abs` | \|gps_speed\| | Absolute speed |

**Final feature count: 12**

### Feature Importance (Top 5)
| Rank | Feature | Importance |
|------|---------|-----------|
| 1 | `accel_z` | 0.184 |
| 2 | `gyro_z` | 0.166 |
| 3 | `accel_x` | 0.148 |
| 4 | `gyro_x` | 0.147 |
| 5 | `accel_magnitude` | 0.142 |

### Model Configuration
```python
XGBClassifier(
    n_estimators     = 200,
    max_depth        = 5,
    learning_rate    = 0.1,
    subsample        = 0.8,
    colsample_bytree = 0.8,
    eval_metric      = 'logloss',
    random_state     = 42
)
```

**Split:** 80% train (9,344) / 20% test (2,336), stratified

### Results

| Metric | Score |
|--------|-------|
| **Test Accuracy** | **100.00%** |
| Precision (Crash) | 1.00 |
| Recall (Crash) | 1.00 |
| F1-Score (Crash) | 1.00 |

> The perfect score is a result of the strong separability between crash and normal events in the sensor data, combined with SMOTE-balanced training.

### Output Files
| File | Description |
|------|-------------|
| `TransitLK-MSFCD-SCD-XGB-2.pkl` | Trained XGBoost model (joblib) |
| `model_metadata.json` | Feature order, threshold (0.75), accuracy, model name |

**Inference threshold:** `0.75` (probability must exceed this to be classified as crash)

---

## Model 2 — Computer Vision Processing (CVP)

**Notebook:** `TransitLK_MSFCD_CVP_TF.ipynb`  
**Framework:** TensorFlow 2.19 / Keras  
**Architecture:** MobileNetV2 (transfer learning) — chosen for its lightweight footprint, suitable for mobile/edge deployment

### Dataset
- **Total images:** 1,742 dashcam frames
- **Classes:** `crash` (948), `normal` (794)
- **Splits:**

| Split | Crash | Normal | Total |
|-------|-------|--------|-------|
| Train | 820 | 685 | 1,505 |
| Valid | 86 | 73 | 159 |
| Test | 42 | 36 | 78 |

- **Dataset path (Drive):** `MyDrive/Colab Notebooks/TransitLK_MSFCD_CVM.v4-transitlk_msfcd_cvm_v4.folder`
- Dataset was roughly balanced (0.84:1 ratio), so class weights were not needed.

### Data Augmentation (Training only)
```python
ImageDataGenerator(
    rescale           = 1./255,
    rotation_range    = 20,
    width_shift_range = 0.2,
    height_shift_range= 0.2,
    shear_range       = 0.2,
    zoom_range        = 0.2,
    horizontal_flip   = True,
    brightness_range  = [0.8, 1.2],
    fill_mode         = 'nearest'
)
```
Validation and test sets use rescaling only.

### Model Architecture
```
Input (224×224×3)
  └── MobileNetV2 (ImageNet weights, frozen initially)
      └── GlobalAveragePooling2D
          └── BatchNormalization
              └── Dropout(0.5)
                  └── Dense(128, relu)
                      └── BatchNormalization
                          └── Dropout(0.3)
                              └── Dense(1, sigmoid)  ← binary output
```

### Training — Two-Phase Strategy

**Phase 1 — Feature Extraction (25 epochs max)**
- Base MobileNetV2 layers frozen
- `Adam(lr=1e-4)`, early stopping on `val_auc` (patience=10)
- Stopped at epoch 23, best weights restored from epoch 13

**Phase 2 — Fine-Tuning (50 epochs max)**
- Top 30 layers of MobileNetV2 unfrozen
- `Adam(lr=1e-5)` (10× lower), early stopping on `val_auc` (patience=15)
- `ReduceLROnPlateau` (factor=0.5, patience=5, min_lr=1e-8)
- Stopped at epoch 39 (epoch 24 in combined history), best weights restored from epoch 24

### Results

| Metric | Score |
|--------|-------|
| **Test Accuracy** | **88.46%** |
| Precision (Crash) | 0.97 |
| Recall (Crash) | 0.81 |
| Precision (Normal) | 0.81 |
| Recall (Normal) | 0.97 |
| ROC-AUC | ~1.00 (validation) |

**Confusion Matrix (Test Set):**
```
              Predicted
              Crash  Normal
Actual Crash  [ 34      8 ]
       Normal [  1     35 ]
```

### Output Files
| File | Description |
|------|-------------|
| `TransitLK_MSFCD_CVP_TF.h5` | Full Keras model (HDF5) |
| `TransitLK_MSFCD_CVP_TF.tflite` | TensorFlow Lite model for Android deployment |

---

## Requirements

### Sensor Model (SDP)
```txt
pandas
numpy
matplotlib
seaborn
scikit-learn
imbalanced-learn
xgboost
joblib
```

### CV Model (CVP)
```txt
tensorflow>=2.19
matplotlib
seaborn
numpy
pandas
scikit-learn
```

Install all:
```bash
pip install pandas numpy matplotlib seaborn scikit-learn imbalanced-learn xgboost joblib tensorflow
```

---

## About TransitLK

**TransitLK** is a project to modernise public bus transit in Sri Lanka. The MSFCD module is the safety intelligence layer, designed to automatically detect crashes in real time using data from the driver's smartphone sensors and an onboard camera, triggering alerts and logging incidents.

---

## Author

**Gowrisankar Sivakumar**  
TransitLK Project — Multi-Sensor Fusion Crash Detection  
GitHub Branch: `Multi-Sensor-Fusion-Crash-Detection`

---

## License

All rights reserved by the author unless otherwise stated.
