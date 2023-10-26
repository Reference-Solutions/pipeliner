import java.io.PrintStream
import org.junit.Test
import org.junit.Assert
import org.junit.Before
import com.bosch.pipeliner.LoggerDynamic

import static org.mockito.Mockito.eq
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify

class LoggerDynamicTest {

    private LoggerDynamic logger
    private PrintStream out

    @Before
    void setUp() {
        logger = new LoggerDynamic(['echo': { String message -> println(message) }])
        out = mock(PrintStream)
        System.setOut(out)
    }

    @Test
    void logInfo() {
        logger.info("Hello")
        verify(out).println(eq("INFO: Hello"))
    }

    @Test
    void logWarn() {
        logger.warn("Hello")
        verify(out).println(eq("WARN: Hello"))
    }

    @Test
    void logError() {
        logger.error("Hello")
        verify(out).println(eq("ERROR: Hello"))
    }

}
