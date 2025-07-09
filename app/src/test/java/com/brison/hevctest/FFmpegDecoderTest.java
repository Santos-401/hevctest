package com.brison.hevctest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// Robolectric is needed for Android specific classes like Uri and Context if not mocking them away entirely.
// If FFmpegDecoder becomes more complex with Android dependencies, Robolectric helps.
// For now, we primarily mock, but having the runner can be useful.
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk=28) // Basic config for Robolectric
public class FFmpegDecoderTest {

    @Mock
    Context mockContext; // Mock for Android Context

    @Mock
    Uri mockInputUri; // Mock for Android Uri

    @Mock
    Uri mockOutputUri; // Mock for Android Uri

    private FFmpegDecoder ffmpegDecoderSpy;

    // Native methods are tricky to mock directly with Mockito if System.loadLibrary succeeds
    // and tries to link them.
    // One approach is to use a spy and mock the native method calls if System.loadLibrary
    // is handled (e.g. by Robolectric or by ensuring the test environment doesn't crash).
    // Another is to not load the library in tests or use a test-specific subclass.

    // For this test, we will spy on FFmpegDecoder and mock the native methods.
    // This requires that the static block for System.loadLibrary either succeeds or
    // is bypassed in the test environment. Robolectric helps here.

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this); // Initialize mocks

        // Spy on a real FFmpegDecoder instance
        // The static block will run. If "ffmpeg_jni" is not available to the test runner,
        // this could throw UnsatisfiedLinkError. Robolectric's NativeShadowExtractor
        // might load a dummy version or it might fail if not configured.
        // A common pattern is to have a way to disable native loading in tests.
        // For now, we assume the test environment handles or ignores System.loadLibrary.
        try {
            // In a pure JUnit environment without Robolectric, System.loadLibrary would fail.
            // Robolectric provides stubs for native methods, so the call to the native methods
            // might not crash but return default values (0, null).
            // We will use `spy` to intercept these calls.
            ffmpegDecoderSpy = spy(new FFmpegDecoder());
        } catch (UnsatisfiedLinkError e) {
            // This might happen if the JNI lib is not found by the test runner.
            // For robust testing of the Java logic, abstracting the native calls
            // via an interface that can be mocked is a good pattern.
            // For now, we proceed assuming the spy can be created.
            System.err.println("Note: UnsatisfiedLinkError during FFmpegDecoder spy creation in test. " +
                               "Native methods will be mocked. " + e.getMessage());
            // If spying on new FFmpegDecoder() fails due to UnsatisfiedLinkError before native methods are mocked,
            // this approach needs refinement (e.g. custom test runner or PowerMockito to mock static System.loadLibrary).
            // However, Robolectric often makes this less of an issue.
            // As a fallback if spy() itself fails:
            // ffmpegDecoderSpy = mock(FFmpegDecoder.class); // Less ideal as it mocks all methods
        }


        // Mock the behavior of the native methods for the spy instance
        // doReturn().when(spy).nativeMethod(...) is an alternative syntax for spies.
        when(ffmpegDecoderSpy.initFFmpeg()).thenReturn(0);
        // Mock decodeFile to return 0 (success) by default
        when(ffmpegDecoderSpy.decodeFile(anyString(), anyString(), anyString())).thenReturn(0);
        // Mock decodeUri to return 0 (success) by default
        when(ffmpegDecoderSpy.decodeUri(any(Context.class), any(Uri.class), any(Uri.class), anyString())).thenReturn(0);
    }

    @Test
    public void decodeVideoFile_shouldCallNativeDecodeFileAndReturnTrueOnSuccess() {
        String inputPath = "input/path.mp4";
        String outputPath = "output/path.yuv";
        String codec = "hevc";

        // Mock native decodeFile to return 0 (success) for this specific test if needed,
        // or rely on the @Before setup.
        when(ffmpegDecoderSpy.decodeFile(eq(inputPath), eq(outputPath), eq(codec))).thenReturn(0);

        boolean result = ffmpegDecoderSpy.decodeVideoFile(inputPath, outputPath, codec);

        assertTrue("decodeVideoFile should return true on native success", result);
        // Verify that the native method was called with the correct parameters
        verify(ffmpegDecoderSpy).decodeFile(eq(inputPath), eq(outputPath), eq(codec));
    }

    @Test
    public void decodeVideoFile_shouldCallNativeDecodeFileAndReturnFalseOnFailure() {
        String inputPath = "input/fail.mp4";
        String outputPath = "output/fail.yuv";
        String codec = "h264";

        // Mock native decodeFile to return -1 (failure)
        when(ffmpegDecoderSpy.decodeFile(eq(inputPath), eq(outputPath), eq(codec))).thenReturn(-1);

        boolean result = ffmpegDecoderSpy.decodeVideoFile(inputPath, outputPath, codec);

        assertFalse("decodeVideoFile should return false on native failure", result);
        // Verify that the native method was called
        verify(ffmpegDecoderSpy).decodeFile(eq(inputPath), eq(outputPath), eq(codec));
    }

    @Test
    public void decodeVideoUri_shouldCallNativeDecodeUriAndReturnTrueOnSuccess() {
        String codec = "hevc";

        when(ffmpegDecoderSpy.decodeUri(eq(mockContext), eq(mockInputUri), eq(mockOutputUri), eq(codec))).thenReturn(0);

        boolean result = ffmpegDecoderSpy.decodeVideoUri(mockContext, mockInputUri, mockOutputUri, codec);

        assertTrue("decodeVideoUri should return true on native success", result);
        verify(ffmpegDecoderSpy).decodeUri(eq(mockContext), eq(mockInputUri), eq(mockOutputUri), eq(codec));
    }

    @Test
    public void decodeVideoUri_shouldCallNativeDecodeUriAndReturnFalseOnFailure() {
        String codec = "h264";

        // Mock native decodeUri to return -1 (failure)
        when(ffmpegDecoderSpy.decodeUri(eq(mockContext), eq(mockInputUri), eq(mockOutputUri), eq(codec))).thenReturn(-1);

        boolean result = ffmpegDecoderSpy.decodeVideoUri(mockContext, mockInputUri, mockOutputUri, codec);

        assertFalse("decodeVideoUri should return false on native failure", result);
        verify(ffmpegDecoderSpy).decodeUri(eq(mockContext), eq(mockInputUri), eq(mockOutputUri), eq(codec));
    }

    @Test
    public void initFFmpeg_shouldBeCallable() {
        // This test mainly ensures the method call doesn't crash and adheres to mock setup.
        // The actual init logic is native.
        int result = ffmpegDecoderSpy.initFFmpeg();
        assertTrue("initFFmpeg should return 0 as mocked", result == 0);
        verify(ffmpegDecoderSpy).initFFmpeg();
    }

    @Test
    public void releaseFFmpeg_shouldBeCallable() {
        // Ensures the method call doesn't crash. Actual release logic is native.
        ffmpegDecoderSpy.releaseFFmpeg();
        verify(ffmpegDecoderSpy).releaseFFmpeg(); // Verifies it was called on the spy
    }
}
