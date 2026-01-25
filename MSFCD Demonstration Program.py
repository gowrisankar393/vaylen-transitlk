import cv2
import numpy as np
import tensorflow as tf
import time
import threading
from datetime import datetime
import tkinter as tk
from tkinter import filedialog, ttk, scrolledtext
from PIL import Image, ImageTk
import queue


class CrashDetectionGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("Crash Detection System - Real Time")
        self.root.geometry("1000x700")

        # Model setup
        self.model_path = 'TransitLK_MSFCD_CVP_TF.tflite'
        self.interpreter = None
        self.input_details = None
        self.output_details = None
        self.load_model()

        # Video variables
        self.cap = None
        self.is_playing = False
        self.current_frame = None
        self.video_path = None

        # Inference variables
        self.inference_interval = 0.5  # 500ms
        self.last_inference_time = 0
        self.current_prediction = "Waiting..."
        self.current_confidence = 0.0
        self.prediction_queue = queue.Queue()

        # Create UI
        self.create_ui()

        # Start inference thread
        self.inference_thread = threading.Thread(target=self.inference_worker, daemon=True)
        self.inference_thread.start()

        # Update UI periodically
        self.update_ui()

        print("🚨 Class Mapping: crash=0 (prob < 0.5), normal=1 (prob >= 0.5)")

    def load_model(self):
        """Load TFLite model"""
        try:
            self.interpreter = tf.lite.Interpreter(model_path=self.model_path)
            self.interpreter.allocate_tensors()
            self.input_details = self.interpreter.get_input_details()
            self.output_details = self.interpreter.get_output_details()
            print(f"✓ Model loaded successfully. Input shape: {self.input_details[0]['shape']}")
        except Exception as e:
            print(f"✗ Error loading model: {e}")

    def create_ui(self):
        """Create the user interface"""
        # Top control frame
        control_frame = tk.Frame(self.root, pady=10)
        control_frame.pack(fill=tk.X, padx=10)

        tk.Button(control_frame, text="📁 Upload Video", command=self.upload_video,
                  font=('Arial', 12), bg='#4CAF50', fg='white', padx=20).pack(side=tk.LEFT, padx=5)

        self.play_btn = tk.Button(control_frame, text="▶️ Play", command=self.toggle_play,
                                  font=('Arial', 12), bg='#2196F3', fg='white', padx=20, state=tk.DISABLED)
        self.play_btn.pack(side=tk.LEFT, padx=5)

        tk.Button(control_frame, text="⏹️ Stop", command=self.stop_video,
                  font=('Arial', 12), bg='#f44336', fg='white', padx=20).pack(side=tk.LEFT, padx=5)

        # Status label
        self.status_label = tk.Label(control_frame, text="No video loaded",
                                     font=('Arial', 10), fg='gray')
        self.status_label.pack(side=tk.RIGHT, padx=10)

        # Main content frame
        content_frame = tk.Frame(self.root)
        content_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

        # Left: Video display
        video_frame = tk.LabelFrame(content_frame, text="Video Feed", font=('Arial', 12, 'bold'))
        video_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        self.video_label = tk.Label(video_frame, bg='black')
        self.video_label.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)

        # Right: Prediction panel
        right_frame = tk.Frame(content_frame, width=300)
        right_frame.pack(side=tk.RIGHT, fill=tk.Y, padx=(10, 0))
        right_frame.pack_propagate(False)

        # Current prediction display
        pred_frame = tk.LabelFrame(right_frame, text="Current Detection",
                                   font=('Arial', 12, 'bold'), height=150)
        pred_frame.pack(fill=tk.X, pady=(0, 10))
        pred_frame.pack_propagate(False)

        self.prediction_label = tk.Label(pred_frame, text="WAITING",
                                         font=('Arial', 24, 'bold'), fg='orange')
        self.prediction_label.pack(expand=True)

        self.confidence_label = tk.Label(pred_frame, text="Confidence: --",
                                         font=('Arial', 12))
        self.confidence_label.pack()

        # Log area
        log_frame = tk.LabelFrame(right_frame, text="Detection Log",
                                  font=('Arial', 12, 'bold'))
        log_frame.pack(fill=tk.BOTH, expand=True)

        self.log_text = scrolledtext.ScrolledText(log_frame, wrap=tk.WORD,
                                                  font=('Courier', 10))
        self.log_text.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        self.log_text.tag_config('crash', foreground='red', font=('Courier', 10, 'bold'))
        self.log_text.tag_config('normal', foreground='green')

        # Settings frame
        settings_frame = tk.LabelFrame(right_frame, text="Settings",
                                       font=('Arial', 10, 'bold'))
        settings_frame.pack(fill=tk.X, pady=(10, 0))

        tk.Label(settings_frame, text="Scan Interval (ms):").pack(side=tk.LEFT, padx=5)
        self.interval_var = tk.StringVar(value="500")
        interval_entry = tk.Entry(settings_frame, textvariable=self.interval_var, width=6)
        interval_entry.pack(side=tk.LEFT, padx=5)
        tk.Button(settings_frame, text="Apply", command=self.update_interval).pack(side=tk.LEFT, padx=5)

    def upload_video(self):
        """Open file dialog to upload video"""
        self.video_path = filedialog.askopenfilename(
            filetypes=[("Video files", "*.mp4 *.avi *.mov"), ("All files", "*.*")]
        )
        if self.video_path:
            self.status_label.config(text=f"Loaded: {self.video_path.split('/')[-1]}")
            self.play_btn.config(state=tk.NORMAL)
            self.log_text.insert(tk.END, f"Video loaded: {self.video_path}\n")
            self.log_text.see(tk.END)

    def toggle_play(self):
        """Toggle play/pause"""
        if not self.is_playing:
            self.start_video()
        else:
            self.pause_video()

    def start_video(self):
        """Start video playback"""
        if not self.video_path:
            return

        self.cap = cv2.VideoCapture(self.video_path)
        if not self.cap.isOpened():
            self.log_text.insert(tk.END, "Error: Could not open video\n")
            return

        self.is_playing = True
        self.play_btn.config(text="⏸️ Pause")
        self.video_loop()

    def pause_video(self):
        """Pause video"""
        self.is_playing = False
        self.play_btn.config(text="▶️ Play")

    def stop_video(self):
        """Stop video"""
        self.is_playing = False
        self.play_btn.config(text="▶️ Play", state=tk.NORMAL if self.video_path else tk.DISABLED)
        if self.cap:
            self.cap.release()
            self.cap = None
        self.video_label.config(image='')
        self.current_prediction = "Waiting..."
        self.current_confidence = 0.0
        # Reset display
        self.prediction_label.config(text="WAITING", fg='orange')
        self.confidence_label.config(text="Confidence: --")

    def video_loop(self):
        """Main video loop"""
        if not self.is_playing or not self.cap:
            return

        ret, frame = self.cap.read()
        if not ret:
            self.stop_video()
            self.log_text.insert(tk.END, "Video ended\n")
            self.log_text.see(tk.END)
            return

        self.current_frame = frame.copy()

        # Check if it's time for inference (every 0.5s)
        current_time = time.time()
        if current_time - self.last_inference_time >= self.inference_interval:
            # Put frame in queue for inference thread
            if self.prediction_queue.empty():
                self.prediction_queue.put(frame.copy())
            self.last_inference_time = current_time

        # Display frame with overlay
        display_frame = self.draw_overlay(frame)

        # Convert to tkinter format
        cv2image = cv2.cvtColor(display_frame, cv2.COLOR_BGR2RGB)
        img = Image.fromarray(cv2image)
        # Resize to fit canvas while maintaining aspect ratio
        img.thumbnail((640, 480))
        imgtk = ImageTk.PhotoImage(image=img)

        self.video_label.imgtk = imgtk
        self.video_label.config(image=imgtk)

        # Schedule next frame
        self.root.after(10, self.video_loop)  # ~30 FPS display

    def draw_overlay(self, frame):
        """Draw prediction overlay on frame"""
        display = frame.copy()
        h, w = display.shape[:2]

        # Color based on prediction
        if self.current_prediction == "CRASH":
            color = (0, 0, 255)  # Red
            bg_color = (0, 0, 100)
        elif self.current_prediction == "NORMAL":
            color = (0, 255, 0)  # Green
            bg_color = (0, 100, 0)
        else:
            color = (255, 165, 0)  # Orange
            bg_color = (100, 50, 0)

        # Draw semi-transparent background
        overlay = display.copy()
        cv2.rectangle(overlay, (10, 10), (350, 90), bg_color, -1)
        cv2.addWeighted(overlay, 0.7, display, 0.3, 0, display)

        # Add text
        cv2.putText(display, f"Status: {self.current_prediction}", (20, 45),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, color, 2)
        cv2.putText(display, f"Conf: {self.current_confidence:.1%}", (20, 75),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 1)

        return display

    def inference_worker(self):
        """Background thread for inference"""
        while True:
            try:
                frame = self.prediction_queue.get(timeout=1)
                label, confidence = self.run_inference(frame)

                # Update GUI from main thread
                self.root.after(0, self.update_prediction, label, confidence)
            except queue.Empty:
                continue

    def run_inference(self, frame):
        """Run TFLite inference - CORRECTED CLASS MAPPING"""
        if self.interpreter is None:
            return "Error", 0.0

        try:
            # Preprocess
            img = cv2.resize(frame, (224, 224))
            img = img.astype(np.float32) / 255.0
            img = np.expand_dims(img, axis=0)

            # Run inference
            self.interpreter.set_tensor(self.input_details[0]['index'], img)
            self.interpreter.invoke()
            output = self.interpreter.get_tensor(self.output_details[0]['index'])

            prob = float(output[0][0])

            # CORRECTED LOGIC:
            # TensorFlow assigns class indices alphabetically:
            # crash (c) = class 0, normal (n) = class 1
            # So: prob < 0.5 means crash, prob >= 0.5 means normal

            if prob < 0.5:
                # Class 0 = Crash (confidence is how far from 0.5 towards 0)
                confidence = 1 - (prob * 2)  # Scale: 0.5->0, 0.0->1.0
                return "CRASH", confidence
            else:
                # Class 1 = Normal (confidence is how far from 0.5 towards 1)
                confidence = (prob - 0.5) * 2  # Scale: 0.5->0, 1.0->1.0
                return "NORMAL", confidence

        except Exception as e:
            print(f"Inference error: {e}")
            return "Error", 0.0

    def update_prediction(self, label, confidence):
        """Update GUI with new prediction"""
        self.current_prediction = label
        self.current_confidence = confidence

        # Update prediction display
        color = 'red' if label == "CRASH" else 'green' if label == "NORMAL" else 'orange'
        self.prediction_label.config(text=label, fg=color)
        self.confidence_label.config(text=f"Confidence: {confidence:.2%}")

        # Add to log
        timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        log_msg = f"[{timestamp}] {label} ({confidence:.2%})\n"

        tag = 'crash' if label == "CRASH" else 'normal'
        self.log_text.insert(tk.END, log_msg, tag)
        self.log_text.see(tk.END)

    def update_interval(self):
        """Update scan interval"""
        try:
            ms = int(self.interval_var.get())
            if ms < 100:
                ms = 100  # Minimum 100ms
            self.inference_interval = ms / 1000.0
            self.log_text.insert(tk.END, f"Scan interval updated to {ms}ms\n")
            self.log_text.see(tk.END)
        except ValueError:
            pass

    def update_ui(self):
        """Periodic UI updates"""
        self.root.after(100, self.update_ui)

    def on_closing(self):
        """Cleanup on window close"""
        self.is_playing = False
        if self.cap:
            self.cap.release()
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    app = CrashDetectionGUI(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    root.mainloop()