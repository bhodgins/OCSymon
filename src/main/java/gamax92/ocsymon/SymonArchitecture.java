package gamax92.ocsymon;

import gamax92.ocsymon.devices.Bank;

import java.util.ArrayList;

import scala.Function2;
import scala.collection.convert.WrapAsScala;
import scala.runtime.BoxesRunTime;
import li.cil.oc.api.Driver;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Signal;
import li.cil.oc.server.PacketSender;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.loomcom.symon.Cpu;
import com.loomcom.symon.devices.Acia;
import com.loomcom.symon.devices.Memory;
import com.loomcom.symon.machines.SymonMachine;

@Architecture.Name("6502 Symon")
public class SymonArchitecture implements Architecture {
	private final Machine machine;

	private SymonVM vm;
	private ConsoleDriver console;
	
	private boolean initialized = false;

	/** The constructor must have exactly this signature. */
	public SymonArchitecture(Machine machine) {
		this.machine = machine;
	}

	public boolean isInitialized() {
		return initialized;
	}

	// Sangar I hate you
	private static int[] ramSizes = {192, 256, 384, 512, 768, 1024};
	private static int calculateMemory(Iterable<ItemStack> components)
	{
		int memory = 0;
		for (ItemStack component : components)
		{
			if (Driver.driverFor(component) instanceof li.cil.oc.api.driver.item.Memory)
			{
				li.cil.oc.api.driver.item.Memory memdrv = (li.cil.oc.api.driver.item.Memory) Driver.driverFor(component);
				memory += ramSizes[(int)(memdrv.amount(component)-1)]*1024;
			}
		}
		return memory;
	}
	
	public boolean recomputeMemory(Iterable<ItemStack> components) {
		int memory = calculateMemory(components);
		if (vm != null) { // OpenComputers, why are you calling this before initialize?
			((SymonMachine) vm.simulator.machine).getBank().resize(memory);
		}
		return memory > 0;
	}

	public boolean initialize() {
		// Set up new VM here
		console = new ConsoleDriver(machine);
		vm = new SymonVM();
		vm.simulator.console = console;
		((SymonMachine) vm.simulator.machine).getBank().init(calculateMemory(machine.host().internalComponents()));
		initialized = true;
		return true;
	}

	public void close() {
		vm = null;
	}

	public ExecutionResult runThreaded(boolean isSynchronizedReturn) {
		try {
			if (!isSynchronizedReturn) {
				// Since our machine is a memory mapped one, parse signals here
				// TODO: Signal device
				Signal signal = null;
				while (true) {
					signal = machine.popSignal();
					if (signal != null) {
						if (signal.name().equals("key_down")) {
							int character = (int) (double) (Double) signal.args()[1]; // castception
							if (character != 0) // Not a character
								console.pushChar(character);
						} else if (signal.name().equals("clipboard")) {
							char[] paste = ((String) signal.args()[1]).toCharArray();
							for (char character: paste) {
								console.pushChar(character);
							}
						}
						((SymonMachine) vm.simulator.machine).getSigDev().queue(signal.name(), signal.args());
					} else
						break;
				}
			}
			vm.run();
			console.flush();

			return new ExecutionResult.Sleep(0);
		} catch (Throwable t) {
			t.printStackTrace();
			return new ExecutionResult.Error(t.toString());
		}
	}

	public void runSynchronized() {
		try {
			vm.run();
			console.flush();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void onConnect() {
		try {
			PacketSender.sendSound(machine.host().world(), machine.host().xPosition(), machine.host().yPosition(), machine.host().zPosition(), ".");
		} catch (Throwable e) {}
	}

	// TODO: Needs more things
	public void load(NBTTagCompound nbt) {
		// Restore Machine

		// Restore Acia
		if (nbt.hasKey("acia")) {
			Acia mACIA = vm.simulator.machine.getAcia();
			NBTTagCompound aciaTag = nbt.getCompoundTag("acia");
			mACIA.setBaudRate(aciaTag.getInteger("baudRate"));
		}

		// Restore Cpu
		if (nbt.hasKey("acia")) {
			Cpu mCPU = vm.simulator.machine.getCpu();
			NBTTagCompound cpuTag = nbt.getCompoundTag("cpu");
			mCPU.setAccumulator(cpuTag.getInteger("rA"));
			mCPU.setProcessorStatus(cpuTag.getInteger("rP"));
			mCPU.setProgramCounter(cpuTag.getInteger("rPC"));
			mCPU.setStackPointer(cpuTag.getInteger("rSP"));
			mCPU.setXRegister(cpuTag.getInteger("rX"));
			mCPU.setYRegister(cpuTag.getInteger("rY"));
		}

		// Restore Ram
		if (nbt.hasKey("ram")) {
			Memory mRAM = vm.simulator.machine.getRam();
			NBTTagCompound ramTag = nbt.getCompoundTag("ram");
			int[] mem = ramTag.getIntArray("mem");
			System.arraycopy(mem, 0, mRAM.getDmaAccess(), 0, mem.length);
		}

		// Restore Rom
		if (nbt.hasKey("rom")) {
			Memory mROM = vm.simulator.machine.getRom();
			NBTTagCompound romTag = nbt.getCompoundTag("rom");
			int[] mem = romTag.getIntArray("mem");
			System.arraycopy(mem, 0, mROM.getDmaAccess(), 0, mem.length);
		}

		// Restore Banked Ram
		if (nbt.hasKey("bank")) {
			Bank mBANK = ((SymonMachine) vm.simulator.machine).getBank();
			NBTTagCompound bankTag = nbt.getCompoundTag("bank");
			mBANK.setBank(bankTag.getInteger("bank"));
			mBANK.setBankSize(bankTag.getInteger("bankSize"));
			mBANK.setMemsize(bankTag.getInteger("size"));
			int[] mem = bankTag.getIntArray("mem");
			ArrayList<Integer> almem = mBANK.getDmaAccess();
			almem.clear();
			for (int v : mem)
				almem.add(v);
		}

		this.console.load(nbt);
	}

	// TODO: Needs more things
	public void save(NBTTagCompound nbt) {
		// Persist Machine

		// Persist Acia
		Acia mACIA = vm.simulator.machine.getAcia();
		if (mACIA != null) {
			NBTTagCompound aciaTag = new NBTTagCompound();
			aciaTag.setInteger("baudRate", mACIA.getBaudRate());
			nbt.setTag("acia", aciaTag);
		}

		// Persist Cpu
		Cpu mCPU = vm.simulator.machine.getCpu();
		if (mCPU != null) {
			NBTTagCompound cpuTag = new NBTTagCompound();
			cpuTag.setInteger("rA", mCPU.getAccumulator());
			cpuTag.setInteger("rP", mCPU.getProcessorStatus());
			cpuTag.setInteger("rPC", mCPU.getProgramCounter());
			cpuTag.setInteger("rSP", mCPU.getStackPointer());
			cpuTag.setInteger("rX", mCPU.getXRegister());
			cpuTag.setInteger("rY", mCPU.getYRegister());
			nbt.setTag("cpu", cpuTag);
		}

		// Persist Ram
		Memory mRAM = vm.simulator.machine.getRam();
		if (mRAM != null) {
			NBTTagCompound ramTag = new NBTTagCompound();
			int[] mem = mRAM.getDmaAccess();
			ramTag.setIntArray("mem", mem);
			nbt.setTag("ram", ramTag);
		}

		// Persist Rom
		Memory mROM = vm.simulator.machine.getRom();
		if (mROM != null) {
			NBTTagCompound romTag = new NBTTagCompound();
			int[] mem = mROM.getDmaAccess();
			romTag.setIntArray("mem", mem);
			nbt.setTag("rom", romTag);
		}

		// Persist Banked Ram
		Bank mBANK = ((SymonMachine) vm.simulator.machine).getBank();
		if (mBANK != null) {
			NBTTagCompound bankTag = new NBTTagCompound();
			bankTag.setInteger("bank", mBANK.getBank());
			bankTag.setInteger("bankSize", mBANK.getBankSize());
			bankTag.setInteger("size", mBANK.getMemsize());
			ArrayList<Integer> almem = mBANK.getDmaAccess();
			int mem[] = new int[almem.size()];
			int i = 0;
			for (int v : almem)
				mem[i++] = v;
			bankTag.setIntArray("mem", mem);
			nbt.setTag("bank", bankTag);
		}

		this.console.save(nbt);
	}
}
