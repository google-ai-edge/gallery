/*
 * Copyright 2025 Neural Forge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.neuralforge.mobile.ui.marketplace

import com.neuralforge.mobile.core.ModelFormat

/**
 * Data source for Model Marketplace
 *
 * In production, this would fetch from a remote API or database.
 * For now, provides curated sample models.
 */
class MarketplaceDataSource {

    /**
     * Get available models from marketplace
     */
    suspend fun getAvailableModels(): List<ModelInfo> {
        return sampleModels
    }

    companion object {
        private val sampleModels = listOf(
            // Large Language Models
            ModelInfo(
                id = "gemma-2b-it",
                name = "Gemma 2B Instruct",
                description = "Google's lightweight instruction-tuned language model. Optimized for on-device inference with excellent quality-to-size ratio. Perfect for chat, Q&A, and text generation tasks.",
                format = ModelFormat.TensorFlowLite,
                size = 1_800_000_000L, // 1.8 GB
                category = ModelCategory.LLM,
                downloads = 15420,
                rating = 4.8f,
                isDownloaded = false,
                previewUrl = null
            ),
            ModelInfo(
                id = "phi-2",
                name = "Phi-2",
                description = "Microsoft's 2.7B parameter language model with impressive performance. Despite its small size, it achieves near state-of-the-art performance on various benchmarks.",
                format = ModelFormat.ONNX,
                size = 2_700_000_000L, // 2.7 GB
                category = ModelCategory.LLM,
                downloads = 8934,
                rating = 4.7f,
                isDownloaded = false,
                previewUrl = null
            ),
            ModelInfo(
                id = "tinyllama-1.1b",
                name = "TinyLlama 1.1B",
                description = "Compact 1.1B parameter language model trained on 3 trillion tokens. Excellent for resource-constrained devices while maintaining good performance.",
                format = ModelFormat.LiteRTLM,
                size = 1_100_000_000L, // 1.1 GB
                category = ModelCategory.LLM,
                downloads = 12301,
                rating = 4.5f,
                isDownloaded = false,
                previewUrl = null
            ),

            // Computer Vision Models
            ModelInfo(
                id = "mobilenet-v3",
                name = "MobileNetV3",
                description = "State-of-the-art mobile image classification model. Optimized for mobile and edge devices with excellent accuracy and efficiency.",
                format = ModelFormat.TensorFlowLite,
                size = 5_400_000L, // 5.4 MB
                category = ModelCategory.VISION,
                downloads = 45231,
                rating = 4.9f,
                isDownloaded = false,
                previewUrl = null
            ),
            ModelInfo(
                id = "yolov8n",
                name = "YOLOv8 Nano",
                description = "Ultra-fast object detection model. Detects 80 different object categories in real-time on mobile devices with high accuracy.",
                format = ModelFormat.TensorFlowLite,
                size = 6_200_000L, // 6.2 MB
                category = ModelCategory.VISION,
                downloads = 38142,
                rating = 4.8f,
                isDownloaded = false,
                previewUrl = null
            ),
            ModelInfo(
                id = "efficientnet-lite",
                name = "EfficientNet Lite",
                description = "Efficient image classification with balanced accuracy and speed. Ideal for real-time classification on mobile devices.",
                format = ModelFormat.TensorFlowLite,
                size = 4_900_000L, // 4.9 MB
                category = ModelCategory.VISION,
                downloads = 29876,
                rating = 4.7f,
                isDownloaded = false,
                previewUrl = null
            ),
            ModelInfo(
                id = "mediapipe-face",
                name = "MediaPipe Face Detection",
                description = "Real-time face detection optimized for mobile. Detects faces with high accuracy even in challenging lighting conditions.",
                format = ModelFormat.TensorFlowLite,
                size = 2_100_000L, // 2.1 MB
                category = ModelCategory.VISION,
                downloads = 52341,
                rating = 4.9f,
                isDownloaded = false,
                previewUrl = null
            ),

            // Audio Models
            ModelInfo(
                id = "whisper-tiny",
                name = "Whisper Tiny",
                description = "OpenAI's lightweight speech recognition model. Supports multiple languages with impressive accuracy despite small size.",
                format = ModelFormat.ONNX,
                size = 75_000_000L, // 75 MB
                category = ModelCategory.AUDIO,
                downloads = 18234,
                rating = 4.6f,
                isDownloaded = false,
                previewUrl = null
            ),
            ModelInfo(
                id = "silero-vad",
                name = "Silero VAD",
                description = "Voice activity detection model. Efficiently detects speech segments in audio streams with minimal latency.",
                format = ModelFormat.ONNX,
                size = 1_500_000L, // 1.5 MB
                category = ModelCategory.AUDIO,
                downloads = 14523,
                rating = 4.7f,
                isDownloaded = false,
                previewUrl = null
            ),

            // Multimodal Models
            ModelInfo(
                id = "clip-vit-base",
                name = "CLIP ViT Base",
                description = "Contrastive language-image pre-training model. Understands both text and images for zero-shot classification and retrieval.",
                format = ModelFormat.ONNX,
                size = 350_000_000L, // 350 MB
                category = ModelCategory.MULTIMODAL,
                downloads = 9876,
                rating = 4.8f,
                isDownloaded = false,
                previewUrl = null
            ),

            // Embedding Models
            ModelInfo(
                id = "sentence-transformers-mini",
                name = "Sentence Transformers Mini",
                description = "Compact sentence embedding model. Generates high-quality embeddings for semantic search and similarity tasks.",
                format = ModelFormat.ONNX,
                size = 120_000_000L, // 120 MB
                category = ModelCategory.EMBEDDING,
                downloads = 16789,
                rating = 4.7f,
                isDownloaded = false,
                previewUrl = null
            ),
            ModelInfo(
                id = "universal-sentence-encoder",
                name = "Universal Sentence Encoder",
                description = "Google's multilingual sentence encoder. Creates dense vector representations for various NLP tasks.",
                format = ModelFormat.TensorFlowLite,
                size = 100_000_000L, // 100 MB
                category = ModelCategory.EMBEDDING,
                downloads = 21345,
                rating = 4.8f,
                isDownloaded = false,
                previewUrl = null
            ),

            // Classification Models
            ModelInfo(
                id = "distilbert-base",
                name = "DistilBERT Base",
                description = "Distilled version of BERT with 97% of its capabilities but 60% faster. Excellent for text classification tasks.",
                format = ModelFormat.ONNX,
                size = 265_000_000L, // 265 MB
                category = ModelCategory.CLASSIFIER,
                downloads = 11234,
                rating = 4.6f,
                isDownloaded = false,
                previewUrl = null
            ),
            ModelInfo(
                id = "bert-tiny",
                name = "BERT Tiny",
                description = "Ultra-compact BERT variant with only 4.4M parameters. Fast inference while maintaining reasonable accuracy.",
                format = ModelFormat.TensorFlowLite,
                size = 17_000_000L, // 17 MB
                category = ModelCategory.CLASSIFIER,
                downloads = 19876,
                rating = 4.5f,
                isDownloaded = false,
                previewUrl = null
            ),

            // More Vision Models
            ModelInfo(
                id = "pose-detection",
                name = "MediaPipe Pose",
                description = "Real-time human pose estimation. Detects 33 body landmarks for fitness, AR, and gesture recognition applications.",
                format = ModelFormat.TensorFlowLite,
                size = 12_300_000L, // 12.3 MB
                category = ModelCategory.VISION,
                downloads = 34567,
                rating = 4.8f,
                isDownloaded = false,
                previewUrl = null
            ),
            ModelInfo(
                id = "hand-tracking",
                name = "MediaPipe Hands",
                description = "Precise hand and finger tracking. Detects 21 hand landmarks for gesture recognition and AR interactions.",
                format = ModelFormat.TensorFlowLite,
                size = 9_800_000L, // 9.8 MB
                category = ModelCategory.VISION,
                downloads = 28901,
                rating = 4.7f,
                isDownloaded = false,
                previewUrl = null
            )
        )
    }
}
