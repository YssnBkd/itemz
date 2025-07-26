# Multi-Stage OCR Processing Architecture

This module implements a multi-stage OCR processing system using PaddleOCR PP-OCRv5 models for receipt text recognition.

## Architecture Overview

The OCR system consists of two main stages:

### Stage 1: Fast Text Detection (<500ms)
- Uses PaddleOCR PP-OCRv5 detection model (`ch_PP-OCRv5_det_infer.onnx`)
- Optimized for real-time camera preview
- Detects text regions and provides quality assessment
- Estimates processing complexity for Stage 2

### Stage 2: Full Text Recognition (1-3 seconds)
- Uses PaddleOCR PP-OCRv5 recognition model (`ch_PP-OCRv5_rec_infer.onnx`)
- High-accuracy text recognition with character-level confidence
- Batch processing for multiple text regions
- Progressive feedback during processing

## Key Components

### OCREngine
Main interface for OCR operations with platform-specific implementations:
- `OCREngineImpl.android.kt` - Android implementation using ONNX Runtime
- `OCREngineImpl.ios.kt` - iOS implementation (simplified for demo)

### OCRModelCache
Manages model loading, caching, and memory usage:
- LRU eviction strategy
- Memory pressure monitoring
- Model warming for reduced cold-start latency

### OCRStageCoordinator
Coordinates the multi-stage processing pipeline:
- Progress tracking with realistic time estimates
- Error handling and recovery
- Performance metrics collection

## Usage Example

```kotlin
val ocrEngine = OCREngineFactory.create()
ocrEngine.initialize()

// Process image with progress tracking
ocrEngine.processImage(imageBytes).collect { stage ->
    when (stage) {
        is OCRProcessingStage.DetectingText -> {
            println("Detection progress: ${stage.progress * 100}%")
        }
        is OCRProcessingStage.RecognizingText -> {
            println("Recognition progress: ${stage.progress * 100}%")
            println("Estimated time remaining: ${stage.estimatedTimeRemainingMs}ms")
        }
        is OCRProcessingStage.Completed -> {
            val result = stage.result
            println("Recognized ${result.recognizedLines.size} text lines")
            println("Overall confidence: ${result.overallConfidence}")
        }
    }
}
```

## Performance Targets

- **Stage 1 Detection**: <500ms processing time
- **Stage 2 Recognition**: 1-3 seconds depending on text density
- **Memory Usage**: <100MB for cached models
- **Accuracy**: >90% text recognition accuracy on standard receipts

## Model Requirements

The system expects the following ONNX models in the `assets/models/` directory:

1. **Detection Model**: `paddleocr_detection.onnx` (~8MB)
   - Input: [1, 3, 640, 640] RGB image tensor
   - Output: Text region coordinates and confidence scores

2. **Recognition Model**: `paddleocr_recognition.onnx` (~12MB)
   - Input: [1, 1, 48, W] grayscale text region tensor
   - Output: Character sequence with confidence scores

## Platform-Specific Notes

### Android
- Uses ONNX Runtime Android with CPU optimization
- Supports model quantization for faster inference
- Integrates with Android's memory management

### iOS
- Currently uses simplified implementation for demo
- Production version would use ONNX Runtime iOS or Core ML
- Could leverage Vision framework for text detection

## Error Handling

The system includes comprehensive error handling:
- Model loading failures
- Inference errors with retry logic
- Memory pressure management
- Network connectivity issues (for future cloud OCR fallback)

## Testing

Unit tests are provided for:
- OCR engine initialization and basic operations
- Text detection and recognition accuracy
- Complete processing pipeline
- Receipt parsing and data extraction

Run tests with:
```bash
./gradlew shared:testDebugUnitTest
```
