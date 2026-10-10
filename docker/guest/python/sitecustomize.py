# Installed only in explicitly UART-enabled task containers via PYTHONPATH.
try:
    import serial
    serial.protocol_handler_packages.append('androidagent_serial')
except ImportError:
    pass
