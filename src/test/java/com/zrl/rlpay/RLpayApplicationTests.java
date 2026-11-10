package com.zrl.rlpay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "rlpay.payment.jobs-enabled=false")
class RLpayApplicationTests {

    @Test
    void contextLoads() {
    }

}
