# Computer Vision Models

This directory contains the ONNX models for receipt detection and OCR processing.

## Required Models

### PaddleOCR PP-OCRv5 Detection Model
- **File**: `paddleocr_detection.onnx`
- **Purpose**: Real-time receipt boundary detection
- **Input**: RGB image (640x640)
- **Output**: Text region bounding boxes with confidence scores
- **Size**: ~8MB
- **Download**: Convert from PaddleOCR PP-OCRv5 detection model

### PaddleOCR PP-OCRv5 Recognition Model
- **File**: `paddleocr_recognition.onnx`
- **Purpose**: Text recognition from detected regions
- **Input**: Cropped text regions
- **Output**: Character sequences with confidence scores
- **Size**: ~12MB
- **Download**: Convert from PaddleOCR PP-OCRv5 recognition model

## Model Conversion

To convert PaddleOCR models to ONNX format:

1. Install PaddleOCR and paddle2onnx
2. Download PP-OCRv5 models from PaddleOCR repository
3. Convert using paddle2onnx tool
4. Optimize for mobile deployment

## Performance Targets

- Detection: <500ms on mid-range devices
- Recognition: 1-3 seconds depending on text density
- Memory usage: <100MB during inference
- Model loading: <2 seconds cold start

## Integration

Models are loaded by the ONNX Runtime engine and cached for performance.
See `ONNXRuntimeEngineImpl` for implementation details.
