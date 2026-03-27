document.addEventListener("DOMContentLoaded", () => {
    // 1. Scroll-triggered Header and Animations
    const header = document.querySelector('header');
    const hiddenElements = document.querySelectorAll('.hidden');
    const heroVideo = document.getElementById('hero-video');

    window.addEventListener('scroll', () => {
        // Header Blur
        if (window.scrollY > 50) {
            header.classList.add('scrolled');
        } else {
            header.classList.remove('scrolled');
        }
    });

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('show');
            }
        });
    }, { threshold: 0.1 });

    hiddenElements.forEach(el => observer.observe(el));

    // Hero Video Play/Pause Observer
    if (heroVideo) {
        const videoObserver = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    heroVideo.play();
                } else {
                    heroVideo.pause();
                }
            });
        }, { threshold: 0 });
        videoObserver.observe(document.getElementById('hero'));
    }

    // 2. Fetch and Parse XML Data
    fetch('models_info.xml')
        .then(response => {
            if (!response.ok) {
                throw new Error("HTTP error " + response.status);
            }
            return response.text();
        })
        .then(str => new window.DOMParser().parseFromString(str, "text/xml"))
        .then(data => {
            const modelsGrid = document.getElementById('models-grid');
            const testSelector = document.getElementById('model-selector');
            const models = data.querySelectorAll('model');

            models.forEach(model => {
                const id = model.querySelector('id')?.textContent || '';
                const name = model.querySelector('name')?.textContent || 'Unnamed Model';
                const description = model.querySelector('description')?.textContent || '';
                const dates = model.querySelector('dates')?.textContent;
                const location = model.querySelector('location')?.textContent;
                const price = model.querySelector('price')?.textContent;
                const image = model.querySelector('image')?.textContent || '';

                // Create Card
                const card = document.createElement('div');
                card.className = 'model-card hidden';
                card.innerHTML = `
                    <div class="model-img-wrapper">
                        <img src="${image}" alt="${name}">
                    </div>
                    <h3>${name}</h3>
                    <p>${description}</p>
                    <div class="model-meta">
                        ${dates ? `<span><i class="ph ph-calendar"></i> ${dates}</span>` : ''}
                        ${location ? `<span><i class="ph ph-map-pin"></i> ${location}</span>` : ''}
                        ${price ? `<span><i class="ph ph-tag"></i> ${price}</span>` : ''}
                    </div>
                `;
                modelsGrid.appendChild(card);
                observer.observe(card); // Observe new card for animation

                // Populate Selector
                const option = document.createElement('option');
                option.value = id;
                option.textContent = name;
                testSelector.appendChild(option);
            });
        })
        .catch(err => {
            console.error("Error loading XML:", err);
            const modelsGrid = document.getElementById('models-grid');
            modelsGrid.innerHTML = `
                <div style="background: rgba(255, 50, 50, 0.1); border: 1px solid rgba(255, 50, 50, 0.3); border-radius: 12px; padding: 2rem; max-width: 600px; text-align: center; margin: 0 auto; grid-column: 1 / -1;">
                    <i class="ph ph-warning-circle" style="font-size: 3rem; color: #ff6b6b; margin-bottom: 1rem;"></i>
                    <h3 style="color: #ff6b6b; margin-bottom: 0.5rem;">CORS or Formatting Error Detected</h3>
                    <p style="color: var(--text-muted); font-size: 0.95rem; line-height: 1.5; margin-bottom: 1rem;">
                        Either the browser blocked reading the <code>models_info.xml</code> (meaning you need to open it via the server) or the XML file itself has a formatting/syntax error.
                    </p>
                    <p style="color: var(--text-muted); font-size: 0.95rem; line-height: 1.5;">
                        <strong style="color: white;">How to fix:</strong> Double-click the <code>Start_Website.bat</code> file. If you already did that, double-check your XML formatting!
                    </p>
                </div>
            `;
        });

    // --- Real Testing UI Logic ---
    const testSelectorGlobal = document.getElementById('model-selector');
    const testBtn = document.getElementById('start-test-btn');
    const uploadBtn = document.getElementById('upload-video-btn');
    const fileInput = document.getElementById('video-upload');
    const videoPlaceholder = document.getElementById('video-placeholder');
    const videoElement = document.getElementById('test-video');
    const overlayUi = document.getElementById('overlay-ui');

    testSelectorGlobal.addEventListener('change', (e) => {
        const selectedName = e.target.options[e.target.selectedIndex].text;
        videoPlaceholder.innerHTML = `
            <i class="ph-duotone ph-check-circle" style="font-size: 4rem; color: var(--accent-light);"></i>
            <p style="margin-top: 1rem; color: var(--accent-light); font-weight: bold; font-size: 1.2rem;">Model Selected: ${selectedName}</p>
            <p style="margin-top: 0.5rem; font-size: 0.9rem;">Start Camera or Upload Video to begin live inference.</p>
        `;
    });

    let stream = null;
    let analyzing = false;
    let closedFramesCount = 0; // Tracks consecutive closed frames
    const DROWSY_THRESHOLD = 3;

    // Create a hidden canvas to extract video frames
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    
    // Canvas for drawing bounding boxes over the video
    const bboxCanvas = document.getElementById('bounding-box-canvas');
    const bboxCtx = bboxCanvas.getContext('2d');

    async function processFrame() {
        if (!analyzing || videoElement.paused || videoElement.ended) return;

        // Set canvas dimensions to match video
        if (canvas.width !== videoElement.videoWidth) {
            canvas.width = videoElement.videoWidth;
            canvas.height = videoElement.videoHeight;
            bboxCanvas.width = videoElement.videoWidth;
            bboxCanvas.height = videoElement.videoHeight;
        }

        // Draw current video frame to hidden canvas
        ctx.drawImage(videoElement, 0, 0, canvas.width, canvas.height);

        // Clear the bounding box overlay canvas
        bboxCtx.clearRect(0, 0, bboxCanvas.width, bboxCanvas.height);

        // Convert to Base64 JPG for speed
        const base64Img = canvas.toDataURL('image/jpeg', 0.6);

        try {
            const response = await fetch('http://localhost:5000/predict', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ image: base64Img })
            });
            const data = await response.json();

            if (data.error) {
                console.error(data.error);
            } else {
                
                // Track Drowsiness State matching webcam_inference.py
                if (data.drowsiness === 'EYES CLOSED') {
                    closedFramesCount++;
                } else {
                    closedFramesCount = 0;
                }

                let warningHtml = '';
                if (closedFramesCount >= DROWSY_THRESHOLD) {
                    warningHtml = '<div style="color: #ff4d4d; font-size: 1.2rem; font-weight: bold; margin-top: 10px; animation: pulseGlow 0.5s infinite alternate; text-shadow: 0 0 10px rgba(255, 77, 77, 0.8);">WARNING: DROWSINESS DETECTED!</div>';
                }

                // Draw Eye Bounding Boxes
                bboxCtx.strokeStyle = '#00ff00';
                bboxCtx.lineWidth = 3;
                if (data.left_box) {
                    const [xmin, ymin, xmax, ymax] = data.left_box;
                    bboxCtx.strokeRect(xmin, ymin, xmax - xmin, ymax - ymin);
                }
                if (data.right_box) {
                    const [xmin, ymin, xmax, ymax] = data.right_box;
                    bboxCtx.strokeRect(xmin, ymin, xmax - xmin, ymax - ymin);
                }

                // Update HUD with Real Data!
                const drowsyColor = data.drowsiness === 'EYES CLOSED' ? '#ff4d4d' : (data.drowsiness === 'NO FACE' ? '#888' : '#4dff4d');
                const foulColor = data.foul_activity === 'Safe Driving' ? '#4dff4d' : '#ff4d4d';

                overlayUi.innerHTML = `
                    <div style="font-weight: bold; margin-bottom: 5px;">Real-Time Inference</div>
                    <div style="font-size: 0.85rem; color: #fff;">
                        Eye State: <strong style="color: ${drowsyColor}">${data.drowsiness}</strong> <br>
                        Activity: <strong style="color: ${foulColor}">${data.foul_activity}</strong> <br>
                        Foul Conf: ${(data.foul_score * 100).toFixed(1)}%
                    </div>
                    ${warningHtml}
                `;
            }
        } catch (error) {
            console.error("Inference Error:", error);
            overlayUi.innerHTML = `<div style="color: red;">Error connecting to API. Is Flask running?</div>`;
        }

        // Request next frame roughly at 10 FPS to not overwhelm CPU but stay responsive
        setTimeout(() => {
            if (analyzing) requestAnimationFrame(processFrame);
        }, 100);
    }

    async function startRealInference() {
        videoPlaceholder.style.display = 'none';
        videoElement.style.display = 'block';
        videoElement.play();

        overlayUi.style.display = 'block';
        overlayUi.innerHTML = 'Connecting to Models...';

        analyzing = true;
        processFrame();
    }

    testBtn.addEventListener('click', async () => {
        try {
            stream = await navigator.mediaDevices.getUserMedia({ video: true });
            videoElement.srcObject = stream;
            startRealInference();
        } catch (err) {
            alert("Camera access denied or none found.");
            console.error(err);
        }
    });

    uploadBtn.addEventListener('click', () => {
        fileInput.click();
    });

    fileInput.addEventListener('change', (e) => {
        if (e.target.files.length > 0) {
            const file = e.target.files[0];
            videoElement.srcObject = null; // Clear webcam stream
            videoElement.src = URL.createObjectURL(file);
            startRealInference();
            
            // Clear the input value so the exact same file triggers 'change' again if selected!
            fileInput.value = '';
        }
    });

    videoElement.addEventListener('ended', () => {
        analyzing = false;
        overlayUi.style.display = 'none';
        videoElement.style.display = 'none';
        bboxCtx.clearRect(0, 0, bboxCanvas.width, bboxCanvas.height); // Clear boxes
        videoPlaceholder.style.display = 'block';
        videoPlaceholder.innerHTML = '<i class="ph ph-check-circle" style="font-size: 3rem; color: var(--accent-light);"></i><p style="margin-top: 10px;">Test Complete</p>';
    });

});
