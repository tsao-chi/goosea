package goosea.cpu

import goosea.isa.compressed.*
import goosea.isa.untyped.*
import goosea.utils.num._
import goosea.mem.*
import goosea.cpu.bus.*
import goosea.isa._

/* VM modes (satp.mode) privileged ISA V20211203 */
val VM_V20211203_MBARE = 0
val VM_V20211203_SV39 = 8
val VM_V20211203_SV48 = 9
val VM_V20211203_SV57 = 10
val VM_V20211203_SV64 = 11

type VMMode = U8

object VMMode {
  val MBARE: VMMode = VM_V20211203_MBARE
  val SV39: VMMode = VM_V20211203_SV39
  val SV48: VMMode = VM_V20211203_SV48
  val SV57: VMMode = VM_V20211203_SV57
  val SV64: VMMode = VM_V20211203_SV64
}

def sext_w32(x: U32): U64 = {
  val i: Int = x.toInt
  val l: Long = i
  U64(l)
}

def sext_w8(x: U8): U64 = {
  val i: Byte = x.toByte
  val l: Long = i
  U64(l)
}

def sext_w16(x: U16): U64 = {
  val i: Short = x.toShort
  val l: Long = i
  U64(l)
}
def zext_d8(x: U8): U64 = x.toU64

def zext_d16(x:U16):U64 = x.toU64
def zext_d32(x:U32):U64 = x.toU64

final class RV64CPU(
                     regs: Regs = Regs(),
                     bus: Bus = Bus(),
                     journal: Journal = JournalDisabled,
                     // used by wfi instruction
                     wfi: Boolean = false,
                     // Virtual memory translation mode
                     vmmode: VMMode = VMMode.MBARE,
                     // physical page number used in virtual memory translation
                     vmppn: U64 = 0,
                   ) {
  def fetchMem(addr: U64): U32 = {
    val paddr = this.translate(addr, Reason.Fetch)
    bus.read32(paddr)
  }

  def readMem8(addr: U64): U8 = {
    val paddr = this.translate(addr, Reason.Read)
    val data = bus.read8(paddr)
    journal.trace(Trace.TraceMem.Read(addr, paddr, 1, data.toString))
    data
  }

  def readMem16(addr: U64): U16 = {
    val paddr = this.translate(addr, Reason.Read)
    val data = bus.read16(paddr)
    journal.trace(Trace.TraceMem.Read(addr, paddr, 2, data.toString))
    data
  }

  def readMem32(addr: U64): U32 = {
    val paddr = this.translate(addr, Reason.Read)
    val data = bus.read32(paddr)
    journal.trace(Trace.TraceMem.Read(addr, paddr, 4, data.toString))
    data
  }

  def readMem64(addr: U64): U64 = {
    val paddr = this.translate(addr, Reason.Read)
    val data = bus.read64(paddr)
    journal.trace(Trace.TraceMem.Read(addr, paddr, 8, data.toString))
    data
  }

  def readReg(reg: Reg): U64 = {
    val x = regs.read(reg)
    journal.trace(Trace.TraceReg.Read(reg, x))
    x
  }

  def writeReg(reg: Reg, value: U64): Unit = {
    regs.write(reg, value)
    journal.trace(Trace.TraceReg.Write(reg, value))
  }

  def ldst_addr(rs1: Reg, offest: Imm32): U64 = regs.read(rs1)+ sext_w32(offest.decodeSext)

  def readPC: U64 = readReg(Reg.PC)

  def writePC(pc: U64) = writeReg(Reg.PC, pc)

  // TODO

  final case class Fetch(pc: U64, bytecode: Bytecode, compressed: Bytecode16)

  def fetch: Fetch = {
    val pc = readPC
    ???
  }

  def fetchForMock(pc: U64): U32 = {
    ???
  }

  def mockFetch(pc: U64, instr: U32): Fetch = {
    ???
  }

  final case class Decode(from: Either[Bytecode, Bytecode16], decoded: Instr)

  def decode(fetch: Fetch): Decode = {
    ???
  }

  def execute(pc: U64, instr: Instr, isCompressed: Boolean): Unit = {
    var nextPC = if (isCompressed) pc + 2 else pc + 4
    journal.trace(Trace.TraceInstr.PrepareExecute(pc, instr))
    instr match {
      case NOP => {}
      // nop is also encoded as `ADDI x0, x0, 0`
      case RV32Instr.ADDI(Reg.X(0), Reg.X(0), Imm32(0)) => {}
      case RV32Instr.LUI(rd, imm) => regs.write(rd, sext_w32(imm.decode))
      case RV32Instr.AUIPC(rd, offest) => regs.write(rd, pc + sext_w32(offest.decode))
      case RV32Instr.JAL(rd, imm) => {
        val offset = sext_w32(imm.decodeSext)
        val target = pc + offset
        regs.write(rd, nextPC)
        nextPC = target
      }
      case RV32Instr.JALR(rd, rs1, imm) => {
        val offset = sext_w32(imm.decodeSext)
        val target = ((regs.read(rs1) + offset) >> 1) << 1
        regs.write(rd, nextPC)
        nextPC = target
      }
      case RV32Instr.BEQ(rs1, rs2, imm) => {
        if (regs.read(rs1) == regs.read(rs2)) {
          nextPC = pc + sext_w32(imm.decodeSext)
        }
      }
      case RV32Instr.BNE(rs1, rs2, imm) => {
        if (regs.read(rs1) != regs.read(rs2)) {
          nextPC = pc + sext_w32(imm.decodeSext)
        }
      }
      case RV32Instr.BLT(rs1, rs2, imm) => {
        if (regs.read(rs1).toLong < regs.read(rs2).toLong) {
          nextPC = pc + sext_w32(imm.decodeSext)
        }
      }
      case RV32Instr.BGE(rs1, rs2, imm) => {
        if (regs.read(rs1).toLong >= regs.read(rs2).toLong) {
          nextPC = pc + sext_w32(imm.decodeSext)
        }
      }
      case RV32Instr.BLTU(rs1, rs2, imm) => {
        if (regs.read(rs1) < regs.read(rs2)) {
          nextPC = pc + sext_w32(imm.decodeSext)
        }
      }
      case RV32Instr.BGEU(rs1, rs2, imm) => {
        if (regs.read(rs1) >= regs.read(rs2)) {
          nextPC = pc + sext_w32(imm.decodeSext)
        }
      }
      case RV32Instr.LB(rd, rs1, offest) => {
        val addr = ldst_addr(rs1, offest)
        val data = readMem8(addr)
        regs.write(rd, sext_w8(data))
      }
      case RV32Instr.LH(rd, rs1, offest) => {
        val addr = ldst_addr(rs1, offest)
        val data = readMem16(addr)
        regs.write(rd, sext_w16(data))
      }
      case RV32Instr.LW(rd, rs1, offest) => {
        val addr = ldst_addr(rs1, offest)
        val data = readMem32(addr)
        regs.write(rd, sext_w32(data))
      }
      case RV64Instr.LD(rd, rs1, offest) => {
        val addr = ldst_addr(rs1, offest)
        val data = readMem64(addr)
        regs.write(rd, data)
      }
      case RV32Instr.LBU(rd, rs1, offest) => {
        val addr = ldst_addr(rs1, offest)
        val data = readMem8(addr)
        regs.write(rd, zext_d8(data))
      }
      case RV32Instr.LHU(rd, rs1, offest) => {
        val addr = ldst_addr(rs1, offest)
        val data = readMem16(addr)
        regs.write(rd, zext_d16(data))
      }
      case RV64Instr.LWU(rd, rs1, offest) => {
        val addr = ldst_addr(rs1, offest)
        val data = readMem32(addr)
        regs.write(rd, zext_d32(data))
      }
      // TODO
    }
    ???
  }

  def tick: Unit = {
    if (this.wfi) {
      return
    }
    val fetch = this.fetch
    val Decode(from, decoded) = this.decode(fetch)
    val isCompressed = from.isRight
    this.execute(fetch.pc, decoded, isCompressed)
  }

  def mockTick(pc: U64, instr: U32): Unit = {
    if (this.wfi) {
      return
    }
    val fetch = this.mockFetch(pc, instr)
    val Decode(from, decoded) = this.decode(fetch)
    val isCompressed = from.isRight
    this.execute(fetch.pc, decoded, isCompressed)
  }

  sealed trait Reason

  object Reason {
    case object Fetch extends Reason

    case object Read extends Reason

    case object Write extends Reason
  }

  def translate(addr: U64, reason: Reason): U64 = {
    if (this.vmmode == VMMode.MBARE) {
      return addr
    }

    // 3.1.6.3 Memory Privilege in mstatus Register
    // The MPRV (Modify PRiVilege) bit modifies the effective privilege mode,
    // i.e., the privilege level at which loads and stores execute.
    // When MPRV=0, loads and stores behave as normal, using the translation and protection
    // mechanisms of the current privilege mode.
    // When MPRV=1, load and store memory addresses are translated and protected,
    // and endianness is applied, as though the current privilege mode were set to MPP.
    // Instruction address-translation and protection are unaffected by the setting of MPRV.
    // MPRV is read-only 0 if U-mode is not supported.
    //val eff_mode = reason match {
    //  case Reason.Fetch => this.mode,
    //}
    ???
  }
}

object RV64CPU {
  def apply(): RV64CPU = new RV64CPU()
}

