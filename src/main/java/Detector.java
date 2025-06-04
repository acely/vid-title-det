import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter.ToMat;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.*;
import org.bytedeco.opencv.opencv_objdetect.*;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_objdetect.*;


public class DetectorV1 {

    public static void main(String[] args) {

        if (args.length != 2) {
            System.err.println("Usage: java Detector <imagePath> <videoPath>");
            System.err.println("Please provide the full path to the template image and the video file.");
            System.exit(1);
        }

        String imagePath = args[0];
        String videoPath = args[1];
        File imgf = new File(imagePath);

        System.out.println("Image Path: " + imagePath);
        System.out.println("Video Path: " + videoPath);

        List<String> detectionTimestamps = new ArrayList<>();

        // Declare OpenCV related Mat objects outside try, so they can be in a finally block for release.
        Mat templateImage = null;
        // VideoCapture videoCapture = null; // Replaced with FFmpegFrameGrabber
        FFmpegFrameGrabber grabber = null; // FFmpegFrameGrabber for video input
        ToMat converter = null; // Converter for FFmpeg frames to OpenCV Mat
        Mat frame = null;

        try {
        	
            // --- Load Template Image (Uncomment to use OpenCV) ---
            System.out.println("Attempting to load template image: " + imagePath);
            templateImage = imread(imgf.getAbsolutePath()); //,IMREAD_GRAYSCALE OpenCV function to read an image from file
            if (templateImage == null || templateImage.empty()) { // Check if image loading failed
                throw new FileNotFoundException("Error: Could not load template image from path: " + imagePath + ". Check path and OpenCV setup.");
            } else {
                System.out.println("Template image loaded successfully (stubbed). Dimensions: " + templateImage.cols() + "x" + templateImage.rows());
            }
            System.out.println("Placeholder: Image loading logic would be here (inside try block).");

            // --- Process Video (Using FFmpegFrameGrabber) ---
            grabber = new FFmpegFrameGrabber(videoPath);
            converter = new OpenCVFrameConverter.ToMat();
            try {
                grabber.start(); // Start the grabber
                System.out.println("Video file opened successfully using FFmpegFrameGrabber. Actual FPS: " + grabber.getFrameRate());
            } catch (org.bytedeco.javacv.FrameGrabber.Exception e) {
                throw new IOException("Error: Could not start FFmpegFrameGrabber for video file: " + videoPath + ". " + e.getMessage(), e);
            }
            frame = new Mat(); // Mat object to store each converted frame
            
            int processingFrameCount = 0;
            // double actualFps = videoCapture.get(Videoio.CAP_PROP_FPS); // Replaced by grabber.getFrameRate()
            double actualFps = grabber.getFrameRate();
            if (actualFps <= 0) actualFps = 30; // Default FPS if not available or invalid
            boolean actualIsTemplateVisible = false;
            double actualAppearanceStartTimeSeconds = -1.0;
            
            System.out.println("Starting ACTUAL video processing loop (using FFmpegFrameGrabber)...");
            Frame capturedFrame;
            while ((capturedFrame = grabber.grabImage()) != null) { // Grab frames one by one
                frame = converter.convert(capturedFrame); // Convert to OpenCV Mat
                if (frame == null || frame.empty()) {
                    continue;
                }
                processingFrameCount++;
                double actualCurrentTimeSeconds = grabber.getTimestamp() / 1000000.0; // Timestamp in seconds
            
                // --- Template Matching ---
                //x=120y=510w=1025h=100
                if (templateImage != null && !templateImage.empty() && !frame.empty()) {
                    int result_cols = frame.cols() - templateImage.cols() + 1;
                    int result_rows = frame.rows() - templateImage.rows() + 1;
            
                    if (result_cols > 0 && result_rows > 0) {
                        Mat result = new Mat(frame.rows(), frame.cols(), CV_32FC1); // Result matrix for match scores
                        // Perform template matching: Compares templateImage with current frame
                        // TM_CCOEFF_NORMED is one of several comparison methods.
                        matchTemplate(frame, templateImage, result, TM_CCORR_NORMED);//TM_CCOEFF_NORMED
            
                        DoublePointer minVal= new DoublePointer();
                        DoublePointer maxVal= new DoublePointer();
                        Point min = new Point();
                        Point max = new Point();
                        minMaxLoc(result, minVal, maxVal, min, max, null);
            
            
                        if (!maxVal.isNull() && maxVal.get() > 0.2) {//@@@check
                            if (!actualIsTemplateVisible) {
                                actualIsTemplateVisible = true;
                                actualAppearanceStartTimeSeconds = actualCurrentTimeSeconds;
                                System.out.println(String.format("Frame %d (%.2fs): Template APPEARED", processingFrameCount, actualCurrentTimeSeconds));
                            }
                        } else {
                            if (actualIsTemplateVisible) {
                                actualIsTemplateVisible = false;
                                String tsEntry = String.format("Appeared: %.2fs, Disappeared: %.2fs (Frames: approx %d to %d)",
                                        actualAppearanceStartTimeSeconds, actualCurrentTimeSeconds,
                                        (int) (actualAppearanceStartTimeSeconds * actualFps) + 1, processingFrameCount - 1);
                                detectionTimestamps.add(tsEntry);
                                System.out.println(String.format("Frame %d (%.2fs): Template DISAPPEARED. Logged: %s", processingFrameCount, actualCurrentTimeSeconds, tsEntry));
                            }
                        }
                        result.release(); // Release the result matrix for this frame
                    }
                }
                // --- End of Template Matching ---
            } // End of actual video processing while loop
            
            System.out.println("ACTUAL video processing finished. Total frames processed: " + processingFrameCount);
            if (actualIsTemplateVisible) {
                // double videoEndTime = (double) processingFrameCount / actualFps; // Replaced by grabber.getTimestamp()
                double videoEndTime = grabber.getTimestamp() / 1000000.0; // Timestamp in seconds
                String tsEntry = String.format("Appeared: %.2fs, Disappeared: at end of video (approx. %.2fs, Frame: %d)",
                        actualAppearanceStartTimeSeconds, videoEndTime, processingFrameCount);
                detectionTimestamps.add(tsEntry);
                System.out.println("LOG: Template was still visible at end of ACTUAL video. Logged: " + tsEntry);
            }
            // --- End of Main Video Processing Loop ---

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
            // --- Release Resources (OpenCV Stub) ---
            // Important to release OpenCV Mat and VideoCapture objects to free native memory.
            // Uncomment these when you use actual OpenCV objects.
            System.out.println("Attempting to release resources...");
            if (grabber != null) {
                try {
                    grabber.stop(); // Stop the grabber
                    grabber.close(); // Close the grabber and release resources
                    System.out.println("FFmpegFrameGrabber stopped and closed.");
                } catch (org.bytedeco.javacv.FrameGrabber.Exception e) {
                    System.err.println("Error stopping/closing FFmpegFrameGrabber: " + e.getMessage());
                }
            }
            // if (videoCapture != null && videoCapture.isOpened()) { // Replaced by grabber release
            //     videoCapture.release(); // Release video capture resources
            //     System.out.println("Video capture released (stubbed).");
            // }
            if (templateImage != null) {
               templateImage.release(); // Release template image Mat
               System.out.println("Template image released.");
            }
            if (frame != null) {
               frame.release(); // Release frame Mat
               System.out.println("Frame mat released.");
            }
            System.out.println("Resource release block executed in finally.");
        }

        // --- Output Final Timestamps ---
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
