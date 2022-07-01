import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.WindowConstants

/**
 * Galaxy evaluator and GUI for interaction with aliens.
 * Created for ICFP Contest 2020.
 * https://icfpcontest2020.github.io
 * https://message-from-space.readthedocs.io/en/latest/personal-appeal.html
 *
 * @author Vladimir Golyakov
 * @version 2022-07-01
 */
class AlienVm {
	companion object {
		//const val ALIEN_URL = "https://api.pegovka.space/aliens/send"
		// Command for load and run docker container of alien proxy on local computer
		// docker run --rm -p 12345:12345 icfpcontest2020/galaxy
		const val ALIEN_URL = "http://localhost:12345/aliens/send"

		@JvmStatic
		fun main(args: Array<String>) {
			val vm = AlienVm()
			vm.loadSystemCommands()
			vm.loadGalaxy()
			//vm.test()
			//vm.testList()
			//vm.testModulate()
			//vm.testConnect()
			vm.openWindow()
			vm.runEvaluator()
			//vm.runDrawProtocol()
		}

	}

	private val nil = CommandNil()
	private val t = CommandTrue()
	private val f = CommandFalse()
	private val cons = CommandCons()
	private fun num(x: Long) = CommandNum(x)
	private fun num(x: Int) = CommandNum(x.toLong())
	private fun string(x: String) = CommandString(x)
	private fun ap(f: Command, x: Command) = CommandAp(f, x)
	private fun inc(x: Command) = ap(CommandInc(), x)
	private fun dec(x: Command) = ap(CommandDec(), x)
	private fun add(x: Command, y: Command) = ap(ap(CommandAdd(), x), y)
	private fun mul(x: Command, y: Command) = ap(ap(CommandMul(), x), y)
	private fun neg(x: Command) = ap(CommandNeg(), x)
	private fun mod(x: Command) = ap(CommandMod(), x)
	private fun dem(x: Command) = ap(CommandDem(), x)
	private fun cons(x: Command, y: Command) = ap(ap(cons, x), y)
	private fun list(vararg commands: Command) = CommandList(*commands)
	private fun if0(x: Command, y: Command, z:Command) = ap(ap(ap(CommandIf0(), x), y), z)
	private fun car(x: Command) = ap(CommandCar(), x)
	private fun cdr(x: Command) = ap(CommandCdr(), x)
	private fun draw(x: Command) = ap(CommandDraw(), x)
	private fun multipledraw(x: Command) = ap(CommandMultipleDraw(), x)
	private fun send(x: Command) = ap(CommandSend(), x)
	private fun f38(x: Command, y: Command) = ap(ap(CommandF38(), x), y)
	private fun interact(x: Command, y: Command, z: Command) = ap(ap(ap(CommandInteract(), x), y), z)

	abstract inner class Command(val name: String) {
		var evaluated: Command? = null

		fun eval(): Command {
			var command = this
			if (command.evaluated != null)
				return command.evaluated!!
			while (true) {
				val result = command.tryEval()
				if (result == command) {
					evaluated = result
					return result
				}
				command = result
			}
		}

		open fun tryEval() = this

		override fun toString() = name

		fun num() = (eval() as? CommandNum)?.value ?: error("Wrong num: ${eval()}")

		// ap(ap(cons, x0), x1)
		fun fromCons(): Pair<Command, Command> {
			val ap1 = this as? CommandAp ?: error("Wrong cons: $this")
			val ap0 = ap1.f as? CommandAp ?: error("Wrong cons: $this")
			if (ap0.f !is CommandCons) error("Wrong cons: $this")
			return Pair(ap0.x, ap1.x)
		}

		fun asList(): CommandList {
			val list = mutableListOf<Command>()
			var cons = this
			while (cons is CommandAp) {
				val (x0, x1) = cons.fromCons()
				if (x0 is CommandAp)
					list.add(x0.asList())
				else
					list.add(x0)
				if (x1 !is CommandAp && x1 != nil)
					list.add(x1)
				cons = x1
			}
			return CommandList(*list.toTypedArray())
		}
	}

	interface Command1 {
		fun execute(x: Command): Command
	}

	interface Command2 {
		fun execute(x: Command, y: Command): Command
	}

	interface Command3 {
		fun execute(x: Command, y: Command, z: Command): Command
	}

	inner class CommandNum(val value: Long) : Command("num") {
		override fun toString() = value.toString()
		override fun equals(other: Any?) = value == (other as? CommandNum)?.value
		override fun hashCode() = value.hashCode()
	}

	inner class CommandString(val value: String) : Command("string") {
		override fun toString() = value
		override fun equals(other: Any?) = value == (other as? CommandString)?.value
		override fun hashCode() = value.hashCode()
	}

	inner class CommandInc : Command("inc"), Command1 {
		override fun execute(x: Command) = num(x.num() + 1)
	}

	inner class CommandDec : Command("dec"), Command1 {
		override fun execute(x: Command) = num(x.num() - 1)
	}

	inner class CommandAdd : Command("add"), Command2 {
		override fun execute(x: Command, y: Command) = num(x.num() + y.num())
	}

	inner class CommandMul : Command("mul"), Command2 {
		override fun execute(x: Command, y: Command) = num(x.num() * y.num())
	}

	inner class CommandDiv : Command("div"), Command2 {
		override fun execute(x: Command, y: Command) = num(x.num() / y.num())
	}

	inner class CommandEq : Command("eq"), Command2 {
		override fun execute(x: Command, y: Command): Command = if (x.num() == y.num()) t else f
	}

	inner class CommandLt : Command("lt"), Command2 {
		override fun execute(x: Command, y: Command): Command = if (x.num() < y.num()) t else f
	}

	inner class CommandMod : Command("mod"), Command1 {
		override fun execute(x: Command) = string(modulate(x))

		private fun modulate(command: Command): String {
			return when (val c = command.eval()) {
				is CommandNum -> modulateNumber(c)
				is CommandNil -> "00"
				is CommandList -> modulateList(c)
				is CommandAp -> modulateCons(c)
				else -> error("Modulate not supported for command: $command")
			}
		}

		private fun modulateNumber(command: CommandNum): String {
			val num = command.value
			val bits = StringBuilder()
			// Sign
			if (num >= 0) {
				bits.append(0)
				bits.append(1)
			} else {
				bits.append(1)
				bits.append(0)
			}
			// Zero
			if (num == 0L) {
				bits.append(0)
				return bits.toString()
			}
			// Width
			val bin = num.toString(2)
			val width = bin.length
			val n = (width + 3) / 4
			// Unary encoded width
			for (i in 0 until n)
				bits.append(1)
			bits.append(0)
			// Zero padding
			for (i in width until n * 4)
				bits.append(0)
			// Binary number
			for (b in bin)
				bits.append(if (b == '1') 1 else 0)
			return bits.toString()
		}

		private fun modulateList(command: CommandList): String {
			val list = command.value
			val bits = StringBuilder("11") // (
			var first = true
			for (element in list) {
				if (first)
					first = false
				else
					bits.append("11") // ,
				bits.append(modulate(element))
			}
			bits.append("00") // )
			return bits.toString()
		}

		private fun modulateCons(command: CommandAp): String {
			val bits = StringBuilder("11")
			val (x, y) = command.fromCons()
			bits.append(modulate(x))
			bits.append(modulate(y))
			return bits.toString()
		}
	}

	inner class CommandDem : Command("dem"), Command1 {
		override fun execute(x: Command) = demodulate((x.eval() as CommandString).value)

		private fun demodulate(bits: String, offset: Array<Int> = arrayOf(0)): Command {
			val header = bits.substring(offset[0], offset[0] + 2)
			offset[0] += 2
			if (header == "00") {
				// nil
				return nil
			}
			if (header == "11") {
				// List or cons
				val x0 = demodulate(bits, offset)
				val x1 = demodulate(bits, offset)
				return cons(x0, x1)
			}
			// Number
			// Parse sign: 01 +, 10 -
			val sign = if (header == "01") 1 else -1
			// Parse width
			var n = 0
			for (i in offset[0] until bits.length) {
				val bit = bits[i]
				if (bit == '1')
					n++
				else
					break
			}
			val width = n * 4
			offset[0] += n + 1
			// Zero
			if (width == 0)
				return num(0)
			// Parse number
			val bin = bits.substring(offset[0], offset[0] + width)
			offset[0] += width
			return num(bin.toLong(2) * sign)
		}
	}

	// ap send (0) = (1, :1678847)
	// :1678847 is decreasing over time at a rate of 1/3 per second and will reach 0 at the icfp contest main round deadline.
	inner class CommandSend : Command("send"), Command1 {
		override fun execute(x: Command): Command {
			val requestBits = CommandMod().execute(x).value
			//println("send: $requestBits: - $x")
			val responseBits = send(requestBits)
			val res = CommandDem().execute(string(responseBits))
			//println("receive: $responseBits - $res")
			return res
		}

		private fun send(bits: String): String {
			val url = URL(ALIEN_URL)
			val conn = url.openConnection() as HttpURLConnection
			conn.requestMethod = "POST"
			conn.doOutput = true
			conn.setRequestProperty("Content-Type", "text/plain")
			conn.setRequestProperty("Content-Length", bits.length.toString())
			conn.useCaches = false
			DataOutputStream(conn.outputStream).use { it.writeBytes(bits) }
			if (conn.responseCode != 200)
				error("Response code: ${conn.responseCode}")
			return conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
		}
	}

	inner class CommandNeg : Command("neg"), Command1 {
		override fun execute(x: Command) = num(-x.num())
	}

	// ap f x
	inner class CommandAp(var f: Command, var x: Command) : Command("ap") {
		override fun tryEval(): Command {
			if (evaluated != null)
				return evaluated!!
			val f1 = f.eval()
			if (f1 is Command1) {
				return f1.execute(x)
			}
			if (f1 is CommandAp) {
				val f2 = f1.f.eval()
				if (f2 is Command2) {
					return f2.execute(f1.x, x)
				}
				if (f2 is CommandAp) {
					val f3 = f2.f.eval()
					if (f3 is Command3) {
						return f3.execute(f2.x, f1.x, x)
					}
				}
			}
			return this
		}

		//override fun toString() = "ap $f $x"
		override fun toString() = "ap($f, $x)"
	}

	// ap ap ap s x0 x1 x2 = ap ap x0 x2 ap x1 x2
	inner class CommandS : Command("s"), Command3 {
		override fun execute(x: Command, y: Command, z: Command) = ap(ap(x, z), ap(y, z))
	}

	// ap ap ap c x0 x1 x2 = ap ap x0 x2 x1
	inner class CommandC : Command("c"), Command3 {
		override fun execute(x: Command, y: Command, z: Command) = ap(ap(x, z), y)
	}

	// ap ap ap b x0 x1 x2 = ap x0 ap x1 x2
	inner class CommandB : Command("b"), Command3 {
		override fun execute(x: Command, y: Command, z: Command) = ap(x, ap(y, z))
	}

	// ap ap t x0 x1 = x0
	inner class CommandTrue : Command("t"), Command2 {
		override fun execute(x: Command, y: Command) = x
	}

	// ap ap f x0 x1 = x1
	inner class CommandFalse : Command("f"), Command2 {
		override fun execute(x: Command, y: Command) = y
	}

	inner class CommandPwr2 : Command("pwr2"), Command1 {
		override fun execute(x: Command): CommandNum {
			val n = x.num().toInt()
			if (n < 0) error("negative pwr2: $n")
			return num(1L shl n)
		}
	}

	// i(x) = x
	inner class CommandI : Command("i"), Command1 {
		override fun execute(x: Command) = x
	}

	// Pair
	// ap ap ap cons x0 x1 x2 = ap ap x2 x0 x1
	open inner class CommandCons(name: String) : Command(name), Command2, Command3 {
		constructor(): this("cons")

		override fun execute(x: Command, y:Command): Command {
			val res = cons(x.eval(), y.eval())
			res.evaluated = res
			return res
		}

		override fun execute(x: Command, y:Command, z:Command) = ap(ap(z, x), y)
	}

	// First
	// ap car ap ap cons x0 x1 = x0
	// ap car x2 = ap x2 t
	inner class CommandCar : Command("car"), Command1 {
		override fun execute(x: Command) = ap(x, t)
	}

	// Tail
	// ap cdr ap ap cons x0 x1 = x1
	// ap cdr x2 = ap x2 f
	inner class CommandCdr : Command("cdr"), Command1 {
		override fun execute(x: Command) = ap(x, f)
	}

	// Nil (Empty List)
	// ap nil x0 = t
	inner class CommandNil : Command("nil"), Command1 {
		override fun execute(x: Command) = t
	}

	// Is Nil (Is Empty List)
	// ap isnil nil = t
	// ap isnil ap ap cons x0 x1 = f
	inner class CommandIsNil : Command("isnil"), Command1 {
		override fun execute(x: Command) = ap(x, ap(t, ap(t, f)))
	}

	// List Construction Syntax
	// ( , )
	// ( )   =   nil
	// ( x0 )   =   ap ap cons x0 nil
	// ( x0 , x1 )   =   ap ap cons x0 ap ap cons x1 nil
	// ( x0 , x1 , x2 )   =   ap ap cons x0 ap ap cons x1 ap ap cons x2 nil
	// ( x0 , x1 , x2 , x5 )   =   ap ap cons x0 ap ap cons x1 ap ap cons x2 ap ap cons x5 nil
	inner class CommandList(vararg val value: Command) : Command("list") {
		override fun tryEval(): Command {
			if (evaluated != null)
				return evaluated!!
			var cons: Command = nil
			for (command in value.reversed())
				cons = cons(command, cons)
			return cons
		}

		//override fun toString() = value.joinToString(prefix = "(", postfix = ")")
		override fun toString() = value.joinToString(prefix = "[", postfix = "]")
	}

	// vec = cons
	inner class CommandVec : CommandCons("vec")

	inner class CommandDraw : Command("draw"), Command1 {
		override fun execute(x: Command): Command {
			val points = x.eval()
			if (points is CommandList) {
				for (vector in points.value) {
					val (a, b) = vector.eval().fromCons()
					if (a != nil && b != nil)
						drawPixel(a.num().toInt(), b.num().toInt())
				}
			} else {
				var cons = points
				while (cons != nil) {
					val (x0, x1) = cons.fromCons()
					val (a, b) = x0.fromCons()
					if (a != nil && b != nil)
						drawPixel(a.num().toInt(), b.num().toInt())
					cons = x1
				}
			}
			drawColor = drawColor.brighter()
			return nil
		}
	}

	inner class CommandCheckerBoard : Command("checkerboard"), Command2 {
		override fun execute(x: Command, y: Command): Command {
			val size = x.num().toInt()
			for (b in 0 until size)
				for (a in 0 until size)
					if (a + b % 2 == 0) {
						drawPixel(a, b)
					}
			return list(num(size), num(y.num()))
		}
	}

	// ap multipledraw nil = nil
	// ap multipledraw ap ap cons x0 x1 = ap ap cons ap draw x0 ap multipledraw x1
	inner class CommandMultipleDraw : Command("multipledraw"), Command1 {
		override fun execute(x: Command): Command {
			val cons = x.eval()
			if (cons == nil) return cons
			val (a, b) = cons.fromCons()
			// Changed draw order
			// return cons(draw(a), multipledraw(b))
			return cons(multipledraw(b), draw(a))
		}
	}

	// ap ap ap if0 0 x0 x1 = x0
	// ap ap ap if0 1 x0 x1 = x1
	inner class CommandIf0 : Command("if0"), Command3 {
		override fun execute(x: Command, y: Command, z: Command) = if (x.num() == 0L) y else z
	}

	// ap modem x0 = ap dem ap mod x0
	// mod is defined on cons, nil and numbers only. So modem function seems to be the way to say that it’s argument consists of numbers and lists only.
	inner class CommandModem : Command("modem"), Command1 {
		override fun execute(x: Command) = dem(mod(x))
	}

	// ap ap f38 x2 x0 = ap ap ap if0 ap car x0 ( ap modem ap car ap cdr x0 , ap multipledraw ap car ap cdr ap cdr x0 ) ap ap ap interact x2 ap modem ap car ap cdr x0 ap send ap car ap cdr ap cdr x0
	// f38(protocol, (flag, newState, data)) = if flag == 0
	// 		then (modem(newState), multipledraw(data))
	// 		else interact(protocol, modem(newState), send(data))
	// newState is always list of list of ... of numbers.
	inner class CommandF38 : Command("f38"), Command2 {
		override fun execute(x: Command, y: Command): CommandAp {
			val protocol = x
			val list = y
			return if0(car(list),
				list(car(cdr(list)), multipledraw(car(cdr(cdr(list))))),
				interact(protocol, car(cdr(list)), send(car(cdr(cdr(list))))))
		}
	}

	// ap ap ap interact x2 x4 x3 = ap ap f38 x2 ap ap x2 x4 x3
	// interact(protocol, state, vector) = f38(protocol, protocol(state, vector))
	//
	// Start the protocol passing nil as the initial state and (0, 0) as the initial point.
	// Then iterate the protocol passing new points along with states obtained from the previous execution.
	// ap ap ap interact x0 nil ap ap vec 0 0 = ( x16 , ap multipledraw x64 )
	// ap ap ap interact x0 x16 ap ap vec x1 x2 = ( x17 , ap multipledraw x65 )
	// ap ap ap interact x0 x17 ap ap vec x3 x4 = ( x18 , ap multipledraw x66 )
	// ap ap ap interact x0 x18 ap ap vec x5 x6 = ( x19 , ap multipledraw x67 )
	inner class CommandInteract : Command("interact"), Command3 {
		override fun execute(x: Command, y: Command, z: Command): CommandAp {
			val protocol = x
			val state = y
			val vector = z
			return f38(protocol, ap(ap(protocol, state), vector))
		}
	}

	// Stateless Drawing Protocol
	// ap interact statelessdraw
	// ap ap statelessdraw nil x1 = ( 0 , nil , ( ( x1 ) ) )
	// statelessdraw = ap ap c ap ap b b ap ap b ap b ap cons 0 ap ap c ap ap b b cons ap ap c cons nil ap ap c ap ap b cons ap ap c cons nil nil
	// ap ap ap interact statelessdraw nil ap ap vec 1 0 = ( nil , ( [1,0] ) )
	// ap ap ap interact statelessdraw nil ap ap vec 2 3 = ( nil , ( [2,3] ) )
	// ap ap ap interact statelessdraw nil ap ap vec 4 1 = ( nil , ( [4,1] ) )
	inner class CommandStatelessDraw : Command("statelessdraw"), Command2 {
		//override fun execute(x: Command, y: Command) = list(num(0), x, list(list(y)))
		override fun execute(x: Command, y: Command) = ap(ap(ap(ap(CommandC(), ap(ap(CommandB(), CommandB()), ap(ap(CommandB(), ap(CommandB(), ap(cons, num(0)))), ap(ap(CommandC(), ap(ap(CommandB(), CommandB()), cons)), ap(ap(CommandC(), cons), nil))))), ap(ap(CommandC(), ap(ap(CommandB(), cons), ap(ap(CommandC(), cons), nil))), nil)), x), y)
	}

	// Stateful Drawing Protocol
	// It gives us back the variable bound to the draw state, so we can set the next pixel with the next call.
	// ap interact statefuldraw
	// ap ap statefuldraw x0 x1 = ( 0 , ap ap cons x1 x0 , ( ap ap cons x1 x0 ) )
	// statefuldraw = ap ap b ap b ap ap s ap ap b ap b ap cons 0 ap ap c ap ap b b cons ap ap c cons nil ap ap c cons nil ap c cons
	// ap ap ap interact statefuldraw nil ap ap vec 0 0 = ( ( ap ap vec 0 0 ) , ( [0,0] ) )
	// ap ap ap interact statefuldraw ( ap ap vec 0 0 ) ap ap vec 2 3 = ( x2 , ( [0,0;2,3] ) )
	// ap ap ap interact statefuldraw x2 ap ap vec 1 2 = ( x3 , ( [0,0;2,3;1,2] ) )
	// ap ap ap interact statefuldraw x3 ap ap vec 3 2 = ( x4 , ( [0,0;2,3;1,2;3,2] ) )
	// ap ap ap interact statefuldraw x4 ap ap vec 4 0 = ( x5 , ( [0,0;2,3;1,2;3,2;4,0] ) )
	inner class CommandStatefulDraw : Command("statefuldraw"), Command2 {
		//override fun execute(x: Command, y: Command) = list(num(0), cons(y, x), list(cons(y, x)))
		override fun execute(x: Command, y: Command) = ap(ap(ap(ap(CommandB(), ap(CommandB(), ap(ap(CommandS(), ap(ap(CommandB(), ap(CommandB(), ap(cons, num(0)))), ap(ap(CommandC(), ap(ap(CommandB(), CommandB()), cons)), ap(ap(CommandC(), cons), nil)))), ap(ap(CommandC(), cons), nil)))), ap(CommandC(), cons)), x), y)
	}

	inner class CommandLink(name: String) : Command(name) {
		override fun tryEval(): Command {
			if (evaluated != null)
				return evaluated!!
			return commands[name] ?: error("Unknown command $name")
		}

		override fun equals(other: Any?) = name == (other as? Command)?.name
		override fun hashCode() = name.hashCode()
	}

	private val commands = mutableMapOf<String, Command>()

	fun loadSystemCommands() {
		val commandsList = listOf(
			CommandInc(),
			CommandDec(),
			CommandAdd(),
			CommandMul(),
			CommandDiv(),
			CommandEq(),
			CommandLt(),
			CommandMod(),
			CommandDem(),
			CommandSend(),
			CommandNeg(),
			CommandS(),
			CommandC(),
			CommandB(),
			t,
			f,
			CommandPwr2(),
			CommandI(),
			cons,
			CommandCar(),
			CommandCdr(),
			nil,
			CommandIsNil(),
			CommandVec(),
			CommandDraw(),
			CommandCheckerBoard(),
			CommandMultipleDraw(),
			CommandIf0(),
			CommandModem(),
			CommandF38(),
			CommandInteract(),
			CommandStatelessDraw(),
			CommandStatefulDraw(),
		)
		for (command in commandsList)
			commands[command.name] = command
		println("System commands loaded")
	}

	fun loadGalaxy() {
		val input = File("src", "galaxy.txt").readLines()
		for (line in input) {
			val words = line.split(' ')
			if (words.size < 3) error("Wrong line: $line")
			val commandName = words[0]
			if (words[1] != "=") error("Wrong line: $line")
			commands[commandName] = parse(words, arrayOf(2))
		}
		println("Galaxy loaded")
	}

	private fun parse(words: List<String>, offset: Array<Int> = arrayOf(0)): Command {
		var paramCount = 0
		while (words[offset[0]] == "ap") {
			offset[0]++
			paramCount++
		}
		val word = words[offset[0]++]
		var command: Command = if (word[0] == ':')
			CommandLink(word)
		else if (word[0].isDigit() || word[0] == '-')
			CommandNum(word.toLong())
		else
			commands[word] ?: CommandLink(word)
		for (p in 0 until paramCount) {
			val param = parse(words, offset)
			command = ap(command, param)
		}
		return command
	}

	fun test() {
		println(inc(inc(num(0))).eval() == num(2))
		println(inc(inc(inc(num(0)))).eval() == num(3))
		val x = num(100)
		println(inc(dec(x)).eval() == x)
		println(dec(inc(x)).eval() == x)
		println(dec(add(x, num(1))).eval() == x)
		println(add(add(num(2), num(3)), num(4)).eval() == num(9))
		println(add(num(2), add(num(3), num(4))).eval() == num(9))
		println(add(mul(num(2), num(3)), num(4)).eval() == num(10))
		println(mul(num(2), add(num(3), num(4))).eval() == num(14))
		println(inc(x).eval() == add(x, num(1)).eval())
		println(dec(x).eval() == add(x, neg(num(1))).eval())
	}

	fun testList() {
		checkList(list(), "()", "nil")
		checkList(list(num(0)), "(0)", "ap ap cons 0 nil")
		checkList(list(num(0), num(1)), "(0, 1)", "ap ap cons 0 ap ap cons 1 nil")
		checkList(list(num(0), num(1), num(2)), "(0, 1, 2)", "ap ap cons 0 ap ap cons 1 ap ap cons 2 nil")
		checkList(list(num(0), num(1), num(2), num(5)), "(0, 1, 2, 5)", "ap ap cons 0 ap ap cons 1 ap ap cons 2 ap ap cons 5 nil")
	}

	private fun checkList(list: CommandList, strList: String, strCons: String) {
		return println(list.toString() == strList && list.eval().toString() == strCons)
	}

	fun testModulate() {
		printModulation(num(1)) // 01100001
		printModulation(num(2)) // 01100010
		printModulation(num(3)) // 01100011
		printModulation(list(num(0)))

		printDemodulation("1101000")
		printDemodulation("11011000011101000")
		printDemodulation("1101100001110111110111101010101011100")
		printDemodulation("11011000011111110101101111111111111111100001000111010010001000000000101001010111000100001010100110101101001111011000011101111111111111111100101100001001010111011111010000101101100101001111111000001000000000000")

		checkMod(nil, "00", "nil")
		checkMod(cons(nil, nil), "110000", "(nil)") // ap ap cons nil nil
		checkMod(cons(num(0), nil), "1101000", "(0)") // ap ap cons 0 nil
		checkMod(cons(num(1), num(2)), "110110000101100010", "ap ap cons 1 2")
		checkMod(cons(num(1), cons(num(2), nil)), "1101100001110110001000", "(1, 2)") // ap ap cons 1 ap ap cons 2 nil
		checkMod(list(num(1), num(2)), "1101100001110110001000", "(1, 2)")
		checkMod(list(num(1), list(num(2), num(3)), num(4)), "1101100001111101100010110110001100110110010000", "(1, (2, 3), 4)")
	}

	private fun checkMod(command: Command, strMod: String, strDem: String) {
		return println(mod(command).eval() == string(strMod) &&	dem(string(strMod)).eval().toString() == strDem)
	}

	private fun printModulation(command: Command) {
		println("$command => ${mod(command).eval()}")
	}

	private fun printDemodulation(bits: String) {
		println("$bits => ${dem(string(bits)).eval()}")
	}

	fun testConnect() {
		val url = URL(ALIEN_URL)
		val postData = "1101000"
		val conn = url.openConnection() as HttpURLConnection
		conn.requestMethod = "POST"
		conn.doOutput = true
		conn.setRequestProperty("Content-Type", "text/plain")
		conn.setRequestProperty("Content-Length", postData.length.toString())
		conn.useCaches = false
		DataOutputStream(conn.outputStream).use { it.writeBytes(postData) }
		println(conn.responseCode)
		val text = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
		println(text)
		printDemodulation(text)
	}

	fun runEvaluator() {
		runGalaxy(0, 0)
//		runGalaxy(0, 0)
//		runGalaxy(0, 0)
//		runGalaxy(0, 0)
//		runGalaxy(0, 0)
//		runGalaxy(0, 0)
//		runGalaxy(0, 0)
//		runGalaxy(0, 0)
//		runGalaxy(8, 4)
//		runGalaxy(2, -8)
//		runGalaxy(3, 6)
//		runGalaxy(0, -14)
//		runGalaxy(-4, 10)
//		runGalaxy(9, -3)
//		runGalaxy(-4, 10)
//		runGalaxy(0, 0)
	}

	private var state: Command = nil

	// Run an interaction protocol called galaxy.
	// ap interact galaxy = ...
	private fun runGalaxy(x: Int = 0, y: Int = 0) {
		println("click ($x, $y)")
		clearImage()
		drawColor = Color.GRAY.darker()

		val protocol = CommandLink("galaxy")
		val vector = cons(num(x), num(y))

		val inter = interact(protocol, state, vector)
		val result = inter.eval()
		val (newState, drawResult) = result.fromCons()
		state = newState
		println("state = ${state.asList()}")
	}

	fun runDrawProtocol() {
		drawStateless(1, 0)
		drawStateless(2, 3)
		drawStateless(4, 1)

		drawStateful(0, 0)
		drawStateful(2, 3)
		drawStateful(1, 2)
		drawStateful(3, 2)
		drawStateful(4, 0)
	}

	private fun drawStateless(x: Int = 0, y: Int = 0) {
		println("drawStateless ($x, $y)")
		val vector = cons(num(x), num(y))
		val inter = interact(CommandStatelessDraw(), nil, vector)
		val result = inter.eval()
		val (newState, drawResult) = result.fromCons()
		state = newState
		println("state = ${state.asList()}")
	}

	private fun drawStateful(x: Int = 0, y: Int = 0) {
		println("drawStateful ($x, $y)")
		val vector = cons(num(x), num(y))
		val inter = interact(CommandStatefulDraw(), state, vector)
		val result = inter.eval()
		val (newState, drawResult) = result.fromCons()
		state = newState
		println("state = ${state.asList()}")
	}

	private val panel = JPanel(BorderLayout(0, 0))
	private var drawColor = Color.WHITE
	private val thickness = 3

	fun openWindow() {
		val frame = JFrame("Galaxy")
		frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
		frame.contentPane.layout = BorderLayout()
		panel.background = Color.BLACK
		frame.contentPane.add(panel, BorderLayout.CENTER)
		frame.setSize(1050, 1050)
		frame.setLocationRelativeTo(null)
		frame.isVisible = true
		panel.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				click(e.x, e.y)
			}
		})
		panel.ignoreRepaint = true
		Thread.sleep(500)
	}

	private fun click(screenX: Int, screenY: Int) {
		val size = panel.size
		val x0 = size.width / 2
		val y0 = size.height / 2
		var x = screenX - x0
		var y = y0 - screenY
		if (x > 0) x += thickness / 2 else x -= thickness / 2
		if (y > 0) y += thickness / 2 else y -= thickness / 2
		x /= thickness
		y /= thickness
		runGalaxy(x, y)
	}

	private fun drawPixel(x: Int, y: Int) {
		val g = panel.graphics ?: return
		val size = panel.size
		val x0 = size.width / 2
		val y0 = size.height / 2
		val screenX = x0 + x * thickness
		val screenY = y0 - y * thickness
		g.color = drawColor
		g.fillRect(screenX - thickness / 2, screenY - thickness / 2, thickness, thickness)
		g.color = Color.BLACK
		g.drawLine(screenX - thickness / 2, screenY - thickness / 2, screenX - thickness / 2, screenY - thickness / 2)
	}

	private fun clearImage() {
		val g = panel.graphics ?: return
		val size = panel.size
		g.color = Color.BLACK
		g.fillRect(0, 0, size.width, size.height)
	}
}
