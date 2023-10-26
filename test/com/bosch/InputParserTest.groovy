import org.junit.Test
import org.junit.Assert
import org.junit.Before
import org.junit.After
import com.bosch.pipeliner.InputParser
import com.bosch.pipeliner.LoggerDynamic
import org.mockito.Spy

class InputParserTest {
    @Spy
    LoggerDynamic logger = new LoggerDynamic(['echo': { String message -> println(message) }])

    private static Map<String, String> environmentFromMap(final Map<String, List<String>> expectedData) {

        TreeMap<String, String> map = new TreeMap<>();

        expectedData.each {
            String key = null
            String value = null
            if (it.key.contains(',')) {
                key = it.key.split(',').join(',PIP_')
                value = it.value[0]
            } else {
                key = it.key
                value = stringifyList(it.value)
            }
            map.put("PIP_" + key, value)
        }
        return map;
    }

    private static String stringifyList(final List<String> listToStringify) {

        String resultingString = ""
        for (int i = 0; i < listToStringify.size(); i++) {
            resultingString += listToStringify[i]
            if (i + 1 < listToStringify.size()) {
                resultingString += " | "
            }
        }

        return resultingString
    }

    /**
     * Test envToMessage function
     */
    @Test
    void testEnvToMessage() {

        Map defaults = [
            defaultInputs: '''
                key1 = v
                key2 = v
                lists = l
                list2s = l
            ''',
            exposed: ['key1', 'key2', 'lists', 'list2s']
        ]

        final Map environment = [
                'pip_key1': 'value',
                'key2': 'v_env',
                'PIP_lists': "one | two | three",
                'pip_list2s': "a | b",
                'pip_lists,pip_list2s': "one, a | one, b",
                'pip_lists,list2s': "two, a | two, b"
        ]

        final String message = '''
                key1 = value
                lists = one | two | three
                list2s = a | b
                lists,list2s = one, a | one, b
        '''

        String defaultInputs = defaults.defaultInputs
        InputParser parser = new InputParser(defaults.exposed, logger)
        String fakeMessage = parser.envToMessage(environment)

        Assert.assertEquals(parser.parseFromMessage(message), parser.parseFromMessage(fakeMessage))

    }

    /**
     * Test the parser can handle input of length 1
     */
    @Test
    void testInputLengthOne() {

        Map defaults = [
            defaultInputs: '''
                key = v
            ''',
            exposed: ['key']
        ]

        final Map<String, List<String>> inputData = [
                'key': ['value']
        ]

        final Map expectedData = inputData
        final Map<String, String> environment = environmentFromMap(inputData)
        String defaultInputs = defaults.defaultInputs
        InputParser parser = new InputParser(defaults.exposed, logger)

        // check environment variables parse
        Map result_env = parser.parse(defaultInputs, environment)
        Assert.assertEquals(expectedData, result_env.args)
    }
    

    /**
     * Test the parser can handle multiple inputs
     */
    @Test
    void testMultipleInputs() {

        Map defaults = [
            defaultInputs: '''
                key = v
                list = l
            ''',
            exposed: ['key', 'list']
        ]

        final Map<String, List<String>> inputData = [
                ('key'): ['value'],
                ('list'): ['one', 'two', 'three']
        ]

        final Map expectedData = inputData
        final Map<String, String> environment = environmentFromMap(inputData)
        String defaultInputs = defaults.defaultInputs
        InputParser parser = new InputParser(defaults.exposed, logger)

        // check environment variables parse
        Map result_env = parser.parse(defaultInputs, environment)
        Assert.assertEquals(expectedData, result_env.args)
    }
    

    /**
     * Test the parser can handle empty inputs
     */
    @Test
    void testEmptyValue() {

        Map defaults = [
            defaultInputs: '''
                key = v
                lists = l
                list2s = l
            ''',
            exposed: ['key', 'lists', 'list2s']
        ]

        final Map<String, List<String>> inputData = [
                ('key'): [],
                ('lists'): [],
                ('list2s'): ['a', 'b'],
        ]

        final Map expectedData = [key: [""], lists: [""], list2s: ['a', 'b']]
        final Map<String, String> environment = environmentFromMap(inputData)
        String defaultInputs = defaults.defaultInputs
        InputParser parser = new InputParser(defaults.exposed, logger)

        // check environment variables parse
        Map result_env = parser.parse(defaultInputs, environment)
        Assert.assertEquals(expectedData, result_env.args)
        Assert.assertEquals(null, result_env.combinations)
    }
}