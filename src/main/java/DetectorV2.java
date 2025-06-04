import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// import org.bytedeco.javacpp.DoublePointer; // Removed as minMaxLoc is removed
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter.ToMat;
import org.bytedeco.opencv.opencv_core.*;
// import org.bytedeco.opencv.opencv_imgproc.*; // matchTemplate is removed, if other imgproc functions are needed, this can be re-added.
// import org.bytedeco.opencv.opencv_objdetect.*; // No longer needed for objdetect after removing matchTemplate
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*; // Retaining for potential future use like CvtColor, etc.
// import static org.bytedeco.opencv.global.opencv_objdetect.*; // No longer needed
import org.bytedeco.opencv.opencv_features2d.*; // Added for ORB and other feature detectors
import static org.bytedeco.opencv.global.opencv_features2d.drawMatches; // Explicit import for drawMatches

public class DetectorV2 {

    // private static final double MATCH_THRESHOLD = 0.8; // Removed
    private static ORB orb;
    private static Mat templateDescriptors;
    private static KeyPointVector templateKeyPoints;
    private static BFMatcher bfMatcher;

    // --- Parameters for ORB Matching and Detection (Tune These!) ---
    // GOOD_MATCH_DISTANCE_THRESHOLD:
    // Defines how close the descriptors must be to be considered a "good" match.
    // For ORB with NORM_HAMMING distance, lower values mean better, more stringent matches.
    // Typical range might be 20-100. Inspect the distances of matches in the 'output_frames' images.
    // If many false matches appear, try decreasing this value.
    // If true objects are missed and their matches have distances slightly above this, try increasing it.
    private static final float GOOD_MATCH_DISTANCE_THRESHOLD = 50.0f;

    // MIN_GOOD_MATCHES_FOR_DETECTION:
    // Minimum number of good matches required to consider the template object detected in the frame.
    // This value depends on the template's size, texture, and distinctiveness, as well as
    // the strictness of GOOD_MATCH_DISTANCE_THRESHOLD.
    // Start with a value like 10-20.
    // If the object is detected with very few good matches that seem correct, you might lower this.
    // If detection is noisy (flickering, false positives), try increasing this value or making
    // GOOD_MATCH_DISTANCE_THRESHOLD stricter.
    private static final int MIN_GOOD_MATCHES_FOR_DETECTION = 10;

    public static void main(String[] args) {

        if (args.length != 2) {
            System.err.println("Usage: java DetectorV2 <imagePath> <videoPath>"); // Updated class name in usage
            System.err.println("Please provide the full path to the template image and the video file.");
            System.exit(1);
        }

        String imagePath = args[0];
        String videoPath = args[1];
        File imgf = new File(imagePath);

        System.out.println("Image Path: " + imagePath);
        System.out.println("Video Path: " + videoPath);

        List<String> detectionTimestamps = new ArrayList<>();

        Mat templateImage = null;
        FFmpegFrameGrabber grabber = null;
        ToMat converter = null;
        Mat frame = null;

        try {
            System.out.println("Attempting to load template image: " + imagePath);
            templateImage = imread(imgf.getAbsolutePath());
            if (templateImage == null || templateImage.empty()) {
                throw new FileNotFoundException("Error: Could not load template image from path: " + imagePath + ". Check path and OpenCV setup.");
            } else {
                System.out.println("Template image loaded successfully. Dimensions: " + templateImage.cols() + "x" + templateImage.rows());
            }

            // Initialize the ORB detector.
            // Default parameters for ORB.create() are often a good starting point:
            // nfeatures=500, scaleFactor=1.2f, nlevels=8, edgeThreshold=31, firstLevel=0, WTA_K=2,
            // scoreType=ORB.HARRIS_SCORE, patchSize=31, fastThreshold=20
            // If detection is poor, you might experiment with these:
            // - Increasing `nfeatures` (e.g., to 1000 or 2000) if the template or objects are small or lack strong features.
            // - Adjusting `scaleFactor` or `nlevels` if objects appear at very different scales than the template.
            orb = ORB.create(); // Using static variable
            bfMatcher = BFMatcher.create(NORM_HAMMING, false); // Initialize BFMatcher

            // Convert template image to grayscale
            Mat grayTemplate = new Mat();
            cvtColor(templateImage, grayTemplate, COLOR_BGR2GRAY);

            // Detect keypoints in the grayscale template
            templateKeyPoints = new KeyPointVector(); // Using static variable
            orb.detect(grayTemplate, templateKeyPoints);

            // Compute descriptors for these keypoints
            templateDescriptors = new Mat(); // Using static variable
            orb.compute(grayTemplate, templateKeyPoints, templateDescriptors);

            System.out.println("Computed " + templateKeyPoints.size() + " ORB keypoints for the template image.");

            // Release the grayTemplate mat
            grayTemplate.release();

            grabber = new FFmpegFrameGrabber(videoPath);
            converter = new OpenCVFrameConverter.ToMat();
            try {
                grabber.start();
                System.out.println("Video file opened successfully using FFmpegFrameGrabber. Actual FPS: " + grabber.getFrameRate());
            } catch (org.bytedeco.javacv.FrameGrabber.Exception e) {
                throw new IOException("Error: Could not start FFmpegFrameGrabber for video file: " + videoPath + ". " + e.getMessage(), e);
            }
            frame = new Mat();

            int processingFrameCount = 0;
            double actualFps = grabber.getFrameRate();
            if (actualFps <= 0) actualFps = 30;
            boolean actualIsTemplateVisible = false; // This will be updated by feature matching logic
            double actualAppearanceStartTimeSeconds = -1.0;

            System.out.println("Starting video processing loop (using FFmpegFrameGrabber)...");
            org.bytedeco.javacv.Frame capturedFrame;
            while ((capturedFrame = grabber.grabImage()) != null) {
                frame = converter.convert(capturedFrame);
                if (frame == null || frame.empty()) {
                    System.err.println("Warning: Grabbed an empty or null frame from video.");
                    if (capturedFrame != null && capturedFrame.imageHeight > 0 && capturedFrame.imageWidth > 0) {
                         System.err.println("Captured frame had dimensions: " + capturedFrame.imageWidth + "x" + capturedFrame.imageHeight);
                    }
                    continue;
                }
                processingFrameCount++;
                double actualCurrentTimeSeconds = grabber.getTimestamp() / 1000000.0;

                // Convert current frame to grayscale
                Mat grayFrame = new Mat();
                cvtColor(frame, grayFrame, COLOR_BGR2GRAY);

                // Detect keypoints in grayFrame
                KeyPointVector frameKeyPoints = new KeyPointVector();
                orb.detect(grayFrame, frameKeyPoints);

                // Compute descriptors for frameKeyPoints
                Mat frameDescriptors = new Mat();
                orb.compute(grayFrame, frameKeyPoints, frameDescriptors);

                if (frameDescriptors.empty()) {
                    // System.out.println("No ORB keypoints found in current frame " + processingFrameCount + ". Skipping matching.");
                    grayFrame.release();
                    frameKeyPoints.close(); // KeyPointVector is a Pointer, so it has close()
                    frameDescriptors.release();
                    continue;
                }

                // Match template descriptors with frameDescriptors
                DMatchVector matches = new DMatchVector();
                DMatchVector goodMatches = new DMatchVector();

                if (templateDescriptors != null && !templateDescriptors.empty() && !frameDescriptors.empty()) {
                    bfMatcher.match(templateDescriptors, frameDescriptors, matches);

                    for (int i = 0; i < matches.size(); i++) {
                        DMatch dmatch = matches.get(i);
                        if (dmatch.distance() < GOOD_MATCH_DISTANCE_THRESHOLD) {
                            goodMatches.push_back(dmatch);
                        }
                    }
                    System.out.println("Frame " + processingFrameCount + ": Raw matches: " + matches.size() + ", Good matches: " + goodMatches.size());

                    // --- Match Visualization ---
                    Mat outputImage = new Mat();
                    // Check if data is valid for drawMatches
                    if (templateImage != null && !templateImage.empty() &&
                        frame != null && !frame.empty() &&
                        templateKeyPoints != null && !templateKeyPoints.isNull() && templateKeyPoints.size() > 0 &&
                        frameKeyPoints != null && !frameKeyPoints.isNull() && frameKeyPoints.size() > 0 &&
                        goodMatches != null && !goodMatches.isNull() && goodMatches.size() > 0) {

                        drawMatches(templateImage, templateKeyPoints, frame, frameKeyPoints,
                                    goodMatches, outputImage, Scalar.ALL, Scalar.ALL,
                                    new org.bytedeco.javacpp.BytePointer(), DrawMatchesFlags.DEFAULT); // Using BytePointer for mask

                        String outputDir = "output_frames";
                        new File(outputDir).mkdirs(); // Create directory if it doesn't exist
                        String filename = outputDir + "/frame_" + processingFrameCount + "_matches.png";
                        imwrite(filename, outputImage);
                        // System.out.println("Saved matches image to " + filename); // Reduce console spam
                    } else {
                        // System.out.println("Frame " + processingFrameCount + ": Not drawing matches due to invalid inputs or no good matches.");
                        // If goodMatches is empty, we might still want to save the raw frame or a side-by-side view,
                        // but for now, we only save if good matches are present and drawable.
                    }
                    outputImage.release();
                    // --- End Match Visualization ---

                    if (goodMatches.size() >= MIN_GOOD_MATCHES_FOR_DETECTION) {
                        if (!actualIsTemplateVisible) {
                            actualIsTemplateVisible = true;
                            actualAppearanceStartTimeSeconds = actualCurrentTimeSeconds;
                            System.out.println(String.format("Frame %d (%.2fs): Template APPEARED (ORB)", processingFrameCount, actualCurrentTimeSeconds));
                        }
                    } else {
                        if (actualIsTemplateVisible) {
                            actualIsTemplateVisible = false;
                            String tsEntry = String.format("Appeared: %.2fs, Disappeared: %.2fs (Frames: approx %d to %d) (ORB)",
                                    actualAppearanceStartTimeSeconds, actualCurrentTimeSeconds,
                                    (int) (actualAppearanceStartTimeSeconds * actualFps) + 1, processingFrameCount - 1);
                            detectionTimestamps.add(tsEntry);
                            System.out.println(String.format("Frame %d (%.2fs): Template DISAPPEARED (ORB). Logged: %s", processingFrameCount, actualCurrentTimeSeconds, tsEntry));
                        }
                    }
                } else {
                     if (templateDescriptors == null || templateDescriptors.empty()) {
                        // System.out.println("Frame " + processingFrameCount + ": Template descriptors are not available. Skipping matching.");
                     }
                     if (frameDescriptors.empty()){
                        // This case is already handled by the continue statement earlier for empty frameDescriptors
                     }
                     // If matches were attempted but templateDescriptors were present, and yet goodMatches is 0 (or below threshold)
                     // the else block for goodMatches.size() >= MIN_GOOD_MATCHES_FOR_DETECTION will handle DISAPPEARED logic.
                     // Explicitly ensure disappearance is logged if it was visible from a previous frame.
                     if (actualIsTemplateVisible) {
                        actualIsTemplateVisible = false;
                        String tsEntry = String.format("Appeared: %.2fs, Disappeared: %.2fs (Frames: approx %d to %d) (ORB - no valid matches or descriptors)",
                                actualAppearanceStartTimeSeconds, actualCurrentTimeSeconds,
                                (int) (actualAppearanceStartTimeSeconds * actualFps) + 1, processingFrameCount -1);
                        detectionTimestamps.add(tsEntry);
                        System.out.println(String.format("Frame %d (%.2fs): Template DISAPPEARED (ORB - no valid matches or descriptors). Logged: %s", processingFrameCount, actualCurrentTimeSeconds, tsEntry));
                    }
                }

                // Release resources created in the loop
                grayFrame.release();
                frameKeyPoints.close();
                frameDescriptors.release();
                // matches.close(); // DMatchVector is a local variable, its owned DMatch objects are managed.
                // goodMatches.close(); // Same for goodMatches.
            } // End of actual video processing while loop

            System.out.println("ACTUAL video processing finished. Total frames processed: " + processingFrameCount);
            if (actualIsTemplateVisible) {
                double videoEndTime = grabber.getTimestamp() / 1000000.0;
                String tsEntry = String.format("Appeared: %.2fs, Disappeared: at end of video (approx. %.2fs, Frame: %d)",
                        actualAppearanceStartTimeSeconds, videoEndTime, processingFrameCount);
                detectionTimestamps.add(tsEntry);
                System.out.println("LOG: Template was still visible at end of video. Logged: " + tsEntry);
            }

        } catch (FileNotFoundException e) {
            System.err.println("File Error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("I/O Error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("An unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            System.out.println("Attempting to release resources...");
            if (grabber != null) {
                try {
                    grabber.stop();
                    grabber.close();
                    System.out.println("FFmpegFrameGrabber stopped and closed.");
                } catch (org.bytedeco.javacv.FrameGrabber.Exception e) {
                    System.err.println("Error stopping/closing FFmpegFrameGrabber: " + e.getMessage());
                }
            }
            if (templateImage != null) {
               templateImage.release();
               System.out.println("Template image released.");
            }
            if (frame != null) {
               frame.release();
               System.out.println("Frame mat released.");
            }
            if (templateDescriptors != null) {
                templateDescriptors.release();
                System.out.println("Template descriptors released.");
            }
            if (templateKeyPoints != null) {
                templateKeyPoints.close();
                System.out.println("Template keypoints closed.");
            }
            // ORB Ptr (orb) and BFMatcher Ptr (bfMatcher) manage their own memory.
            System.out.println("Resource release block executed in finally.");
        }

        System.out.println("\n--- Detection Timestamp Log ---");
        if (detectionTimestamps.isEmpty()) {
            System.out.println("No template detections were recorded.");
        } else {
            for (String entry : detectionTimestamps) {
                System.out.println(entry);
            }
        }
        System.out.println("-----------------------------");
    }
}
