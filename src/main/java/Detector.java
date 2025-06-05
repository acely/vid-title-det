import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter.ToMat;
import org.bytedeco.opencv.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;


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

        Mat templateImage = null;
        FFmpegFrameGrabber grabber = null; // FFmpegFrameGrabber for video input
        ToMat converter = null; // Converter for FFmpeg frames to OpenCV Mat

        try {
        	
            // --- Load Template Image ---
            System.out.println("Attempting to load template image: " + imagePath);
            templateImage = imread(imgf.getAbsolutePath(),IMREAD_GRAYSCALE); // OpenCV function to read an image from file
            if (templateImage == null || templateImage.empty()) { // Check if image loading failed
                throw new FileNotFoundException("Error: Could not load template image from path: " + imagePath + ". Check path and OpenCV setup.");
            } else {
                System.out.println("Template image dimensions: " + templateImage.cols() + "x" + templateImage.rows());
            }

            // --- Process Video (Using FFmpegFrameGrabber) ---
            grabber = new FFmpegFrameGrabber(videoPath);
//            grabber.setImageWidth(1280);
//            grabber.setImageHeight(720);
            grabber.setPixelFormat(CV_8UC1);
//            grabber.setVideoOption(imagePath, videoPath);
            converter = new OpenCVFrameConverter.ToMat();
            try {
                grabber.start(); // Start the grabber
                System.out.println("Video file opened successfully using FFmpegFrameGrabber. Actual FPS: " + grabber.getFrameRate());
            } catch (org.bytedeco.javacv.FrameGrabber.Exception e) {
                throw new IOException("Error: Could not start FFmpegFrameGrabber for video file: " + videoPath + ". " + e.getMessage(), e);
            }
            
            int processingFrameCount = 0;
            // double actualFps = videoCapture.get(Videoio.CAP_PROP_FPS); // Replaced by grabber.getFrameRate()
            double actualFps = grabber.getFrameRate();
            if (actualFps <= 0) actualFps = 30; // Default FPS if not available or invalid
            boolean actualIsTemplateVisible = false;
            double actualAppearanceStartTimeSeconds = -1.0;
            
            System.out.println("Starting ACTUAL video processing loop (using FFmpegFrameGrabber)...");
            Frame capturedFrame;
            Mat frame = null;
            Rect roi = new Rect(120, 510, 1100, 130);//x=120y=510w=1025h=110
            while ((capturedFrame = grabber.grabImage()) != null) { // Grab frames one by one
                frame = converter.convert(capturedFrame).apply(roi); // Convert to OpenCV Mat
                if (frame == null || frame.empty()) {
                    continue;
                }
                processingFrameCount++;
                if (processingFrameCount == 1) {
                    System.out.println("First video frame dimensions: " + frame.cols() + "x" + frame.rows());
                }
                double actualCurrentTimeSeconds = grabber.getTimestamp() / 1000000.0; // Timestamp in seconds
            
                // --- Template Matching ---
                
                if (templateImage != null && !templateImage.empty() && !frame.empty()) {
                    int result_cols = frame.cols() - templateImage.cols() + 1;
                    int result_rows = frame.rows() - templateImage.rows() + 1;
            
                    if (result_cols > 0 && result_rows > 0) {
                        Mat result = new Mat(result_rows, result_cols, CV_32FC1); // Result matrix for match scores
                        matchTemplate(frame, templateImage, result, TM_CCORR_NORMED);
//                        matchTemplate(frame, templateImage, result, TM_CCOEFF_NORMED);
            
                        DoublePointer minVal= new DoublePointer(1);
                        DoublePointer maxVal= new DoublePointer(1);
                        Point min = new Point();
                        Point max = new Point();
                        minMaxLoc(result, minVal, maxVal, min, max, null);
            
                        if (maxVal.get() > 0.85) {
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
                frame.release();
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
            if (templateImage != null) {
               templateImage.release(); // Release template image Mat
               System.out.println("Template image released.");
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
    
    public static Scalar randColor(){
        int b,g,r;
        b= ThreadLocalRandom.current().nextInt(0, 255 + 1);
        g= ThreadLocalRandom.current().nextInt(0, 255 + 1);
        r= ThreadLocalRandom.current().nextInt(0, 255 + 1);
        return new Scalar (b,g,r,0);
     }
    
}
