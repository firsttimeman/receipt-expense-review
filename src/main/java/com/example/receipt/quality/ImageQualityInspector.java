package com.example.receipt.quality;

import com.example.receipt.config.ReceiptProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class ImageQualityInspector {
    private final ReceiptProperties properties;

    public ImageQualityResult inspect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new ImageQualityResult(ImageQualityStatus.UNREADABLE, null, null, "빈 파일입니다.");
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return new ImageQualityResult(ImageQualityStatus.UNREADABLE, null, null,
                        "지원되는 이미지로 해석할 수 없습니다.");
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width < properties.getQuality().getMinWidth() || height < properties.getQuality().getMinHeight()) {
                return new ImageQualityResult(ImageQualityStatus.NEEDS_RECAPTURE, width, height,
                        "최소 해상도 %dx%d를 충족하지 않습니다."
                                .formatted(properties.getQuality().getMinWidth(), properties.getQuality().getMinHeight()));
            }
            return new ImageQualityResult(ImageQualityStatus.ACCEPTABLE, width, height, "기본 품질 검사를 통과했습니다.");
        } catch (IOException exception) {
            return new ImageQualityResult(ImageQualityStatus.UNREADABLE, null, null, "이미지를 읽는 중 오류가 발생했습니다.");
        }
    }
}
