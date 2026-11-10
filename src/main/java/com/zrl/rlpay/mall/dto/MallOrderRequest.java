package com.zrl.rlpay.mall.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.util.List;

public final class MallOrderRequest {
    private MallOrderRequest() {
    }

    /** 创建草稿时提交商品和数量。 */
    public record Create(
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items
    ) {
    }

    /** 修改草稿时提交当前版本和完整的新明细。 */
    public record Update(
            @NotNull @Min(0) @JsonDeserialize(using = IntegerFieldDeserializer.class) Integer version,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items
    ) {
    }

    /** 一项商品及其购买数量，单价由服务端查询。 */
    public record Item(
            @NotBlank @Size(max = 32) String productCode,
            @NotNull @Min(1) @Max(99) @JsonDeserialize(using = IntegerFieldDeserializer.class) Integer quantity
    ) {
    }

    public static final class IntegerFieldDeserializer extends JsonDeserializer<Integer> {
        /** 拒绝把小数或字符串自动转换成数量或版本号。 */
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
