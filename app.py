import os
import cv2
import base64
import numpy as np
import tensorflow as tf
from flask import Flask, request, jsonify
from flask_cors import CORS
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
from tensorflow.keras.models import load_model, Sequential
from tensorflow.keras.layers import Input, Lambda, GlobalAveragePooling2D, Dropout, Dense
from tensorflow.keras.applications import MobileNetV2

app = Flask(__name__)
CORS(app) # Allow cross-origin requests from frontend

# Paths relative to this script
BASE_DIR = os.path.dirname(os.path.abspath(__name__))
PARENT_DIR = os.path.abspath(os.path.join(BASE_DIR, '..'))

DROWSINESS_MODEL_PATH = os.path.join(PARENT_DIR, 'drowsiness_eye_model.h5')
FACE_TASK_PATH = os.path.join(PARENT_DIR, 'face_landmarker.task')
FOUL_MODEL_PATH = os.path.join(PARENT_DIR, 'Foul_Detection', 'driver_model_weights.weights.h5')
CRASH_MODEL_PATH = os.path.join(PARENT_DIR, 'TransitLK_MSFCD_CVP_TF.tflite')

# --- 1. Load MediaPipe ---
print("Loading MediaPipe Face Landmarker...")
base_options = python.BaseOptions(model_asset_path=FACE_TASK_PATH)
options = vision.FaceLandmarkerOptions(
    base_options=base_options,
    output_face_blendshapes=False,
    output_facial_transformation_matrixes=False,
    num_faces=1,
    min_face_detection_confidence=0.5,
    min_face_presence_confidence=0.5,
    min_tracking_confidence=0.5
)
detector = vision.FaceLandmarker.create_from_options(options)

# --- 2. Load Drowsiness CNN ---
print("Loading Drowsiness Model...")
try:
    drowsiness_model = load_model(DROWSINESS_MODEL_PATH)
except:
    print("Warning: Could not load Drowsiness Model.")
    drowsiness_model = None

# Left and Right eye indices from MediaPipe
LEFT_EYE = [362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398]
RIGHT_EYE = [33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246]

def get_eye_crop(frame, landmarks, eye_indices, img_w, img_h):
    x_coords = [int(landmarks[idx].x * img_w) for idx in eye_indices]
    y_coords = [int(landmarks[idx].y * img_h) for idx in eye_indices]
    x_min, x_max = min(x_coords), max(x_coords)
    y_min, y_max = min(y_coords), max(y_coords)
    pad_x = int((x_max - x_min) * 0.2)
    pad_y = int((y_max - y_min) * 0.4)
    x_min = max(0, x_min - pad_x)
    x_max = min(img_w, x_max + pad_x)
    y_min = max(0, y_min - pad_y)
    y_max = min(img_h, y_max + pad_y)
    eye_img = frame[y_min:y_max, x_min:x_max]
    return eye_img, (x_min, y_min, x_max, y_max)

def preprocess_eye(eye_img):
    if eye_img.size == 0: return None
    gray_eye = cv2.cvtColor(eye_img, cv2.COLOR_BGR2GRAY)
    resized_eye = cv2.resize(gray_eye, (80, 80))
    normalized_eye = resized_eye / 255.0
    input_tensor = np.expand_dims(normalized_eye, axis=-1)
    return np.expand_dims(input_tensor, axis=0)

# --- 3. Load Foul Detection Model ---
print("Loading Foul Detection Model...")
def build_driver_model():
    base_model = MobileNetV2(input_shape=(224, 224, 3), include_top=False, weights=None)
    model = Sequential([
        Input(shape=(224, 224, 3)),
        Lambda(tf.keras.applications.mobilenet_v2.preprocess_input),
        base_model,
        GlobalAveragePooling2D(),
        Dropout(0.2),
        Dense(5, activation='softmax')
    ])
    return model

foul_model = build_driver_model()
try:
    foul_model.load_weights(FOUL_MODEL_PATH)
except:
    print("Warning: Could not load Foul Detection Weights.")
    foul_model = None

foul_class_names = ['Other Activities', 'Safe Driving', 'Talking Phone', 'Texting Phone', 'Turning']

# --- 4. Load Crash Detection Model ---
print("Loading Crash Detection Model...")
try:
    crash_interpreter = tf.lite.Interpreter(model_path=CRASH_MODEL_PATH)
    crash_interpreter.allocate_tensors()
    crash_input_details = crash_interpreter.get_input_details()
    crash_output_details = crash_interpreter.get_output_details()
except Exception as e:
    print(f"Warning: Could not load Crash Detection Model. Error: {e}")
    crash_interpreter = None

# --- API Endpoints ---
@app.route('/predict', methods=['POST'])
def predict():
    try:
        data = request.json
        img_data = base64.b64decode(data['image'].split(',')[1])
        np_arr = np.frombuffer(img_data, np.uint8)
        frame = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
        if frame is None:
            return jsonify({'error': 'Invalid image'})

        img_h, img_w, _ = frame.shape
        model_id = data.get('model', '')

        result = {
            'drowsiness': 'NO FACE',
            'drowsiness_score': 0.0,
            'foul_activity': 'Unknown',
            'foul_score': 0.0,
            'left_box': None,
            'right_box': None,
            'crash_status': 'Unknown',
            'crash_confidence': 0.0
        }

        # --- Drowsiness & Foul Logic ---
        if model_id == 'drowsiness-foul':
            rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
            det_results = detector.detect(mp_image)

            if det_results.face_landmarks and drowsiness_model is not None:
                face_landmarks = det_results.face_landmarks[0]
                left_img, left_box = get_eye_crop(frame, face_landmarks, LEFT_EYE, img_w, img_h)
                right_img, right_box = get_eye_crop(frame, face_landmarks, RIGHT_EYE, img_w, img_h)
                
                # Draw Face boxes to return to Front End
                result['left_box'] = left_box
                result['right_box'] = right_box

                l_tensor = preprocess_eye(left_img)
                r_tensor = preprocess_eye(right_img)

                if l_tensor is not None and r_tensor is not None:
                    l_pred = drowsiness_model.predict(l_tensor, verbose=0)[0][0]
                    r_pred = drowsiness_model.predict(r_tensor, verbose=0)[0][0]
                    avg_pred = (float(l_pred) + float(r_pred)) / 2.0
                    result['drowsiness_score'] = avg_pred
                    if avg_pred < 0.5:
                        result['drowsiness'] = 'EYES CLOSED'
                    else:
                        result['drowsiness'] = 'EYES OPEN'
            
            # --- Foul Detection Logic ---
            if foul_model is not None:
                foul_img = cv2.resize(frame, (224, 224))
                foul_array = np.expand_dims(tf.keras.preprocessing.image.img_to_array(foul_img), axis=0)
                foul_preds = foul_model.predict(foul_array, verbose=0)[0]
                max_idx = np.argmax(foul_preds)
                result['foul_activity'] = foul_class_names[max_idx]
                result['foul_score'] = float(foul_preds[max_idx])

        # --- Crash Detection Logic ---
        elif model_id == 'crash-detection':
            if crash_interpreter is not None:
                crash_img = cv2.resize(frame, (224, 224))
                crash_img = crash_img.astype(np.float32) / 255.0
                crash_img = np.expand_dims(crash_img, axis=0)

                crash_interpreter.set_tensor(crash_input_details[0]['index'], crash_img)
                crash_interpreter.invoke()
                crash_output = crash_interpreter.get_tensor(crash_output_details[0]['index'])

                prob = float(crash_output[0][0])
                if prob < 0.5:
                    result['crash_status'] = "CRASH"
                    result['crash_confidence'] = 1 - (prob * 2)
                else:
                    result['crash_status'] = "NORMAL"
                    result['crash_confidence'] = (prob - 0.5) * 2

        return jsonify(result)
        
    except Exception as e:
        import traceback
        return jsonify({'error': str(e), 'trace': traceback.format_exc()})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
