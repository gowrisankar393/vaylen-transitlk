import cv2
import dlib
import numpy as np
from tensorflow.keras.models import load_model
from imutils import face_utils
from scipy.spatial import distance as dist

# --- CONFIGURATION ---
# Drowsiness Thresholds
EYE_AR_THRESH = 0.25    # If eye ratio < 0.25, eyes are closed
EYE_AR_CONSEC_FRAMES = 20 # Number of frames eyes must be closed to trigger alarm

# Class Labels
# 0: other, 1: safe, 2: talking, 3: texting, 4: turning
CLASSES = ["Distracted/Other", "Safe Driving", "Talking on Phone", "Texting", "Turning"]

# --- HELPER FUNCTION: Calculate Eye Aspect Ratio (EAR) ---
def eye_aspect_ratio(eye):
    # Vertical distances
    A = dist.euclidean(eye[1], eye[5])
    B = dist.euclidean(eye[2], eye[4])
    # Horizontal distance
    C = dist.euclidean(eye[0], eye[3])
    # The Ratio
    ear = (A + B) / (2.0 * C)
    return ear

# --- INITIALIZATION ---
print("[INFO] Loading AI Model...")
behavior_model = load_model("driver_behavior.h5")

print("[INFO] Loading Facial Landmark Predictor...")
detector = dlib.get_frontal_face_detector()
predictor = dlib.shape_predictor("shape_predictor_68_face_landmarks.dat")

# Get indexes for left and right eyes
(lStart, lEnd) = face_utils.FACIAL_LANDMARKS_IDXS["left_eye"]
(rStart, rEnd) = face_utils.FACIAL_LANDMARKS_IDXS["right_eye"]

print("[INFO] Starting Camera...")
cap = cv2.VideoCapture(0) # Change to 1 if using an external USB camera
COUNTER = 0

while True:
    ret, frame = cap.read()
    if not ret:
        print("Failed to grab frame")
        break
    
    # Resize for speed
    frame = cv2.resize(frame, (800, 600))
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

    # =================================================
    # SYSTEM 1: BEHAVIOR DETECTION (Using MobileNet)
    # =================================================
    # Preprocess the frame for the model (224x224)
    roi = cv2.resize(frame, (224, 224))
    roi = roi.astype("float") / 255.0
    roi = np.expand_dims(roi, axis=0)
    
    # Get Prediction
    preds = behavior_model.predict(roi, verbose=0)[0]
    label_idx = np.argmax(preds)
    label = CLASSES[label_idx]
    confidence = preds[label_idx]
    
    # Display Logic
    # If "Safe" -> Green. If "Turning" -> Blue. "Phone/Texting" -> Red.
    if label_idx == 1: # Safe
        color = (0, 255, 0) 
    elif label_idx == 4: # Turning
        color = (255, 255, 0)
    else: # Danger (Phone, Texting, Other)
        color = (0, 0, 255)

    label_text = f"Behavior: {label} ({confidence*100:.1f}%)"
    cv2.putText(frame, label_text, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)

    # =================================================
    # SYSTEM 2: DROWSINESS DETECTION (Using Dlib)
    # =================================================
    rects = detector(gray, 0)
    
    for rect in rects:
        shape = predictor(gray, rect)
        shape = face_utils.shape_to_np(shape)
        
        # Extract Eyes
        leftEye = shape[lStart:lEnd]
        rightEye = shape[rStart:rEnd]
        leftEAR = eye_aspect_ratio(leftEye)
        rightEAR = eye_aspect_ratio(rightEye)
        
        # Average the EAR together
        ear = (leftEAR + rightEAR) / 2.0
        
        # Visualization: Draw eyes
        leftEyeHull = cv2.convexHull(leftEye)
        rightEyeHull = cv2.convexHull(rightEye)
        cv2.drawContours(frame, [leftEyeHull], -1, (0, 255, 0), 1)
        cv2.drawContours(frame, [rightEyeHull], -1, (0, 255, 0), 1)

        # Logic: Check if eyes are closed
        if ear < EYE_AR_THRESH:
            COUNTER += 1
            # If closed for enough frames...
            if COUNTER >= EYE_AR_CONSEC_FRAMES:
                cv2.putText(frame, "DROWSINESS ALERT!", (250, 300), 
                            cv2.FONT_HERSHEY_SIMPLEX, 1.5, (0, 0, 255), 4)
        else:
            COUNTER = 0
            
        cv2.putText(frame, f"Eye Ratio: {ear:.2f}", (10, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 0, 0), 2)

    # Show the window
    cv2.imshow("Driver Monitoring System", frame)
    
    # Press 'q' to quit
    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()