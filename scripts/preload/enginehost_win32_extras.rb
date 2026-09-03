# enginehost_win32_extras.rb
# Author: the Droidtop project (2026)
#
# Creative Commons CC0: to the extent possible under law, the authors have
# dedicated all copyright and related and neighboring rights to this script to
# the public domain worldwide. https://creativecommons.org/publicdomain/zero/1.0/
#
# Win32API implementations that win32_wrap.rb leaves to its tolerant stub
# (which answers 0). A stubbed 0 is wrong in ways games notice: settings read
# from Game.ini come back empty, Shift_JIS/UTF-16 conversions return nothing,
# struct copies never happen, and "is my window in front" is always no.
# JoiPlay's android-mkxp implements these natively for exactly that reason;
# these are the same functions, in Ruby, on top of win32_wrap's contract:
# Win32API_Impl::<Dll>::<Function>#call(args) with string arguments passed as
# Ruby strings that act as the caller's buffers (memcpy_string writes into
# them in place).
#
# Loaded after win32_wrap.rb. Nothing here replaces an implementation that
# file already has.

module Win32API_Impl
	FAKE_HWND = 42 unless const_defined?(:FAKE_HWND)

	module EnginehostExtras
		# The game directory is the current directory for RGSS; INI paths are
		# relative to it ("./Game.ini") or absolute.
		def self.ini_path(name)
			path = name.to_s.dup
			path = path[0, path.index("\0")] if path.include?("\0")
			path
		end

		def self.read_ini(file)
			return {} unless File.exist?(file)
			sections = {}
			current = nil
			File.foreach(file) do |line|
				text = line.sub(/[;#].*$/, "").strip
				next if text.empty?
				if text =~ /\A\[(.*)\]\z/
					current = $1.strip.downcase
					sections[current] ||= []
				elsif current && text.include?("=")
					key, value = text.split("=", 2)
					sections[current] << [key.strip, value.to_s.strip]
				end
			end
			sections
		end

		def self.write_ini(file, app, key, value)
			lines = File.exist?(file) ? File.readlines(file).map(&:chomp) : []
			section_start = lines.index { |l| l.strip.downcase == "[#{app.downcase}]" }
			if section_start.nil?
				lines << "" unless lines.empty?
				lines << "[#{app}]"
				lines << "#{key}=#{value}"
			else
				section_end = lines.length
				(section_start + 1...lines.length).each do |i|
					if lines[i].strip =~ /\A\[.*\]\z/
						section_end = i
						break
					end
				end
				existing = (section_start + 1...section_end).find { |i| lines[i].split("=", 2)[0].to_s.strip.downcase == key.downcase }
				if existing
					lines[existing] = "#{key}=#{value}"
				else
					lines.insert(section_end, "#{key}=#{value}")
				end
			end
			File.open(file, "w") { |f| f.write(lines.join("\n") + "\n") }
			true
		rescue
			false
		end

		def self.cstr(buffer)
			s = buffer.to_s
			i = s.index("\0")
			i ? s[0, i] : s
		end

		# Windows code page numbers to Ruby encodings, for the two conversions
		# Japanese RPG Maker scripts actually make.
		def self.encoding_for(code_page)
			case code_page
			when 0, 1, 3, 932 then "Windows-31J"
			when 65001 then "UTF-8"
			when 1252 then "Windows-1252"
			when 936 then "GBK"
			when 949 then "CP949"
			when 950 then "Big5"
			else "Windows-31J"
			end
		end
	end

	module Kernel32
		unless const_defined?(:GetPrivateProfileString)
			class GetPrivateProfileString
				def call(args)
					app, key, default, buffer, size, file = args
					value = nil
					ini = EnginehostExtras.read_ini(EnginehostExtras.ini_path(file))
					section = ini[app.to_s.downcase]
					if section
						pair = section.find { |k, _| k.downcase == key.to_s.downcase }
						value = pair[1] if pair
					end
					value = default.to_s if value.nil?
					value = value[0, size.to_i - 1] if size.to_i > 0
					memcpy_string(buffer, value + "\0")
					value.length
				end
			end
		end

		unless const_defined?(:GetPrivateProfileInt)
			class GetPrivateProfileInt
				def call(args)
					app, key, default, file = args
					ini = EnginehostExtras.read_ini(EnginehostExtras.ini_path(file))
					section = ini[app.to_s.downcase]
					pair = section && section.find { |k, _| k.downcase == key.to_s.downcase }
					pair ? pair[1].to_i : default.to_i
				end
			end
		end

		unless const_defined?(:WritePrivateProfileString)
			class WritePrivateProfileString
				def call(args)
					app, key, value, file = args
					EnginehostExtras.write_ini(EnginehostExtras.ini_path(file), app.to_s, key.to_s, EnginehostExtras.cstr(value)) ? 1 : 0
				end
			end
		end

		unless const_defined?(:MultiByteToWideChar)
			class MultiByteToWideChar
				def call(args)
					code_page, _flags, source, source_length, buffer, buffer_chars = args
					bytes = source.to_s
					bytes = bytes[0, source_length] if source_length.to_i >= 0 && source_length.to_i < bytes.length
					bytes = EnginehostExtras.cstr(bytes) if source_length.to_i < 0
					wide = bytes.dup.force_encoding(EnginehostExtras.encoding_for(code_page.to_i)).encode("UTF-16LE", :invalid => :replace, :undef => :replace)
					wide << "\0\0".force_encoding("UTF-16LE") if source_length.to_i < 0
					chars = wide.bytesize / 2
					return chars if buffer_chars.to_i == 0
					memcpy_string(buffer, wide.dup.force_encoding("BINARY")[0, buffer_chars.to_i * 2])
					[chars, buffer_chars.to_i].min
				rescue
					0
				end
			end
		end

		unless const_defined?(:WideCharToMultiByte)
			class WideCharToMultiByte
				def call(args)
					code_page, _flags, source, source_chars, buffer, buffer_bytes = args
					wide = source.to_s.dup.force_encoding("UTF-16LE")
					if source_chars.to_i < 0
						terminator = wide.index("\0".encode("UTF-16LE"))
						wide = wide[0, terminator] if terminator
					elsif source_chars.to_i * 2 < wide.bytesize
						wide = wide.byteslice(0, source_chars.to_i * 2).force_encoding("UTF-16LE")
					end
					narrow = wide.encode(EnginehostExtras.encoding_for(code_page.to_i), :invalid => :replace, :undef => :replace).force_encoding("BINARY")
					narrow << "\0" if source_chars.to_i < 0
					return narrow.bytesize if buffer_bytes.to_i == 0
					memcpy_string(buffer, narrow[0, buffer_bytes.to_i])
					[narrow.bytesize, buffer_bytes.to_i].min
				rescue
					0
				end
			end
		end

		unless const_defined?(:RtlMoveMemory)
			class RtlMoveMemory
				def call(args)
					destination, source, length = args
					if destination.is_a?(String) && source.is_a?(String)
						memcpy_string(destination, source.to_s[0, length.to_i])
					end
					0
				end
			end
		end

		unless const_defined?(:GetUserDefaultLangID)
			class GetUserDefaultLangID
				def call(args)
					language = begin
						System.user_language.to_s
					rescue
						""
					end
					case language[0, 2].downcase
					when "ja" then 0x0411
					when "zh" then 0x0804
					when "ko" then 0x0412
					when "de" then 0x0407
					when "fr" then 0x040C
					when "es" then 0x0C0A
					when "ru" then 0x0419
					else 0x0409
					end
				end
			end
		end

		unless const_defined?(:GetCurrentThreadId)
			class GetCurrentThreadId
				def call(args)
					1
				end
			end
		end
	end

	module User32
		unless const_defined?(:GetForegroundWindow)
			class GetForegroundWindow
				def call(args)
					Win32API_Impl::FAKE_HWND
				end
			end
		end

		unless const_defined?(:GetActiveWindow)
			class GetActiveWindow
				def call(args)
					Win32API_Impl::FAKE_HWND
				end
			end
		end

		unless const_defined?(:FindWindow)
			class FindWindow
				def call(args)
					User32::FindWindowA.new.call(args)
				end
			end
		end

		unless const_defined?(:GetSystemMetrics)
			class GetSystemMetrics
				def call(args)
					case args[0].to_i
					when 0 then (Graphics.width rescue 640)
					when 1 then (Graphics.height rescue 480)
					else 0
					end
				end
			end
		end

		unless const_defined?(:GetWindowRect)
			class GetWindowRect
				def call(args)
					User32::GetClientRect.new.call([Win32API_Impl::FAKE_HWND, args[1]])
				end
			end
		end

		unless const_defined?(:MessageBoxA)
			class MessageBoxA
				def call(args)
					text = EnginehostExtras.cstr(args[1])
					begin
						msgbox(text)
					rescue
						System.puts(text) rescue nil
					end
					1
				end
			end
		end

		unless const_defined?(:MessageBox)
			class MessageBox
				def call(args)
					User32::MessageBoxA.new.call(args)
				end
			end
		end
	end
end
