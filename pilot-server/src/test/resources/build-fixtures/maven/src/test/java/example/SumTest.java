package example;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SumTest {
  @Test void sumsAndRejectsOverflow() {
    assertEquals(6, Sum.add(2, 4));
    assertEquals(-2, Sum.add(0, -2));
    assertThrows(ArithmeticException.class, () -> Sum.add(Integer.MAX_VALUE, 1));
  }
}
